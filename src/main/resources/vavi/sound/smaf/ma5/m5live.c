/*
 * m5live - yamaha's MA-5 emulator, M5_EmuSmw5.dll, played live by midi.
 *
 * The dll is what mmftool plays a SMAF file on. Besides the file player (MaSound_Create, _Load,
 * _Start ...) it has a door for midi messages as they happen, SetMidiMsg, which is what mmftool's
 * own piano roll plays through: the dll initialized for it (MaSound_EmuInitialize with the mode 0,
 * see EmuMIDIInitialize of mmftool's emusmw5.c) takes a channel message as it stands and the
 * exclusives of yamaha in their MA-3 real time form, f0 43 79 06 7f ... f7 - the voices and the
 * waves of a song, and a reset. Nothing else is asked of it, so there is no sequencer here and no
 * clock: an event is played when it arrives, and the arriving is the timing.
 *
 * Initialized that way the dll is the sound source without the driver above it, and it has no
 * voices at all: the host sends them first (mmftool's DefMA3_16.vm3, as its gui does), as the
 * exclusives they are.
 *
 * <b>The audio is not ours.</b> Initialized, the dll opens a waveOut device of its own and keeps
 * it fed from a thread of its own, silence and all, for as long as it is up. So all this program
 * does is carry the messages in and wait - what comes out goes to the emulated PC's waveOut, and
 * the host takes it from there (see vavi.sound.smaf.ma5.Ma5Device).
 *
 * It is built without a C runtime - kernel32 and nothing else - for the reason rts4c is: the
 * emulated PC has kernel32 and not the runtime a modern mingw links against by default.
 *
 *   i686-w64-mingw32-gcc -O2 -nostdlib -ffreestanding -fno-builtin -e _entry \
 *       -Wl,--subsystem,console -o m5live.exe m5live.c -lkernel32
 *
 * Usage: m5live <live.m5l>
 *
 * The file is one the host keeps appending to while this reads it, which is the only channel into
 * the emulated PC there is. All little endian:
 *
 *   'M' '5' 'L' 0
 *   u32 rate       what the dll synthesizes at; 22050, 32000, 44100 or 48000, it answers anything
 *                  else with silence rather than an error, so anything else is refused here
 *   events, each  u32 length; length bytes, padded out to a multiple of four
 *
 * A length of 0xffffffff is the host saying there will be no more, and is how this ends. A record
 * only half written when it is read is left where it is until the rest of it turns up, so the host
 * may append whenever it likes without a lock.
 *
 * It says "m5live: ready" once the dll is up and taking messages, which is what the host waits for.
 *
 * @author Naohide Sano
 */
#include <windows.h>

/** how much of the growing file to ask for at a poll; a midi stream is nothing like this fast */
#define POLL        1024

/** what the unparsed tail of the file may grow to while the rest of a record is awaited */
#define PENDING     65536

/** the longest single message, the wave of a song rather than anything a key press makes */
#define MAXMESSAGE  32768

/** the host saying there will be no more */
#define STOP        0xffffffffu

/* the dll's own, as mmftool's emusmw5.c declares them: plain cdecl */
typedef int (*EMUINITIALIZE)(DWORD rate, DWORD mode, BYTE *work);
typedef int (*DEVICECONTROL)(int, int, int, int);
typedef int (*EMUTERMINATE)(void);
typedef int (*SETMIDIMSG)(BYTE *message, DWORD length);

static HANDLE out;

/* -nostdlib, so whatever gcc decides to call has to be here */

void *memset(void *p, int c, unsigned n) {
    unsigned char *b = (unsigned char *) p;
    while (n--) *b++ = (unsigned char) c;
    return p;
}

void *memcpy(void *d, const void *s, unsigned n) {
    unsigned char *a = (unsigned char *) d;
    const unsigned char *b = (const unsigned char *) s;
    while (n--) *a++ = *b++;
    return d;
}

static void puts_(const char *s) {
    DWORD written = 0;
    int length = 0;
    while (s[length]) length++;
    WriteFile(out, s, length, &written, NULL);
}

static void puti_(int v) {
    char buf[16];
    int i = sizeof buf - 1;
    unsigned u = v < 0 ? -(unsigned) v : (unsigned) v;
    buf[i] = 0;
    do { buf[--i] = (char) ('0' + u % 10); u /= 10; } while (u);
    if (v < 0) buf[--i] = '-';
    puts_(buf + i);
}

static void puth_(unsigned v) {
    static const char digits[] = "0123456789abcdef";
    char buf[3];
    buf[0] = digits[(v >> 4) & 15];
    buf[1] = digits[v & 15];
    buf[2] = 0;
    puts_(buf);
}

/** says what went wrong on stdout and goes, so the caller has more than an exit code to go on */
static void die(const char *what, int code) {
    puts_("m5live: error ");
    puts_(what);
    puts_("\n");
    ExitProcess(code);
}

/** the command line split on spaces, "quotes" honoured; see rts4c for why not the W one */
static int split(char *line, char **argv, int max) {
    int argc = 0;
    while (*line && argc < max) {
        int quoted = 0;
        while (*line == ' ' || *line == '\t') line++;
        if (!*line) break;
        if (*line == '"') { quoted = 1; line++; }
        argv[argc++] = line;
        while (*line && (quoted ? *line != '"' : (*line != ' ' && *line != '\t'))) line++;
        if (*line) *line++ = 0;
    }
    return argc;
}

static void *alloc(unsigned bytes) {
    void *p = VirtualAlloc(NULL, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!p) die("out of memory", 4);
    return p;
}

/** the whole of what was asked for, however many reads that takes */
static int readFully(HANDLE file, unsigned char *buffer, unsigned length) {
    unsigned got = 0;
    while (got < length) {
        DWORD read = 0;
        if (!ReadFile(file, buffer + got, length - got, &read, NULL)) {
            return 0;
        }
        if (read == 0) {
            /* the host has not written it yet */
            Sleep(1);
            continue;
        }
        got += read;
    }
    return 1;
}

static unsigned le32(const unsigned char *p) {
    return p[0] | ((unsigned) p[1] << 8) | ((unsigned) p[2] << 16) | ((unsigned) p[3] << 24);
}

static FARPROC need(HMODULE dll, const char *name) {
    FARPROC f = GetProcAddress(dll, name);
    if (!f) {
        puts_("m5live: error no ");
        puts_(name);
        puts_(" in M5_EmuSmw5.dll\n");
        ExitProcess(3);
    }
    return f;
}

void entry(void) {
    char *argv[8];
    int argc, stopped = 0;
    HANDLE file;
    HMODULE dll;
    EMUINITIALIZE emuInitialize;
    DEVICECONTROL deviceControl;
    EMUTERMINATE emuTerminate;
    SETMIDIMSG setMidiMsg;
    BYTE *work;
    unsigned char header[8], *pending, *message;
    unsigned rate, pendingLength = 0, i, messages = 0, failed = 0;

    out = GetStdHandle(STD_OUTPUT_HANDLE);
    argc = split(GetCommandLineA(), argv, 8);
    if (argc < 2) die("usage: m5live <live.m5l>", 2);

    /*
     * FILE_SHARE_WRITE because the host has this open and is still writing to it - that is the
     * whole idea - and read to the end of what is there rather than to the end of the file, since
     * the file has no end while this runs.
     */
    file = CreateFileA(argv[1], GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                       OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) die("cannot read the events", 5);
    if (!readFully(file, header, sizeof header)) die("the events end early", 5);
    if (header[0] != 'M' || header[1] != '5' || header[2] != 'L' || header[3] != 0) {
        die("that is not a live m5l stream", 5);
    }
    rate = le32(header + 4);
    if (rate != 22050 && rate != 32000 && rate != 44100 && rate != 48000) {
        die("the dll plays at 22050, 32000, 44100 or 48000 only", 6);
    }

    dll = LoadLibraryA("M5_EmuSmw5.dll");
    if (!dll) die("cannot load M5_EmuSmw5.dll", 3);
    emuInitialize = (EMUINITIALIZE) need(dll, "MaSound_EmuInitialize");
    deviceControl = (DEVICECONTROL) need(dll, "MaSound_DeviceControl");
    emuTerminate = (EMUTERMINATE) need(dll, "MaSound_EmuTerminate");
    setMidiMsg = (SETMIDIMSG) need(dll, "SetMidiMsg");

    /*
     * What the work area is, nobody knows: mmftool found the dll wants its address to end in
     * 0x81 and that is all there is to it. A page from VirtualAlloc ends in 0x00.
     */
    work = (BYTE *) alloc(4096) + 0x81;
    if (emuInitialize(rate, 0, work)) die("MaSound_EmuInitialize would not (M5_EmuHw.dll there?)", 7);
    /*
     * What yamaha's own ATS-MA5 does after it (mmftool's memo/M5Emu2.txt): mmftool does the
     * last four only (its Emu526829), and with those alone the dll opens its device and never
     * writes to it.
     */
    if (deviceControl(0x0d, 0, 0, 0) || deviceControl(0x02, 0x1f, 0, 0)
            || deviceControl(0x04, 0x1f, 0, 0) || deviceControl(0x03, 0, 0x1f, 0x1f)
            || deviceControl(0x05, 2, 0, 0) || deviceControl(0x06, 0, 0, 0)
            || deviceControl(0x08, 2, 0, 0) || deviceControl(0x09, 0, 0, 0)) {
        die("MaSound_DeviceControl would not", 7);
    }

    pending = (unsigned char *) alloc(PENDING);
    message = (unsigned char *) alloc(MAXMESSAGE);

    puts_("m5live: ready, rate ");
    puti_((int) rate);
    puts_("\n");

    while (!stopped) {
        DWORD read = 0;
        unsigned at = 0;
        if (pendingLength < PENDING) {
            unsigned want = PENDING - pendingLength;
            if (!ReadFile(file, pending + pendingLength, want < POLL ? want : POLL, &read, NULL)) {
                read = 0;
            }
            pendingLength += read;
        }
        while (pendingLength - at >= 4) {
            unsigned length = le32(pending + at), padded;
            if (length == STOP) {
                stopped = 1;
                at += 4;
                break;
            }
            if (length > MAXMESSAGE) die("a message longer than the stream allows", 6);
            padded = (length + 3) & ~3u;
            /* half a record: leave it where it is until the host has written the rest */
            if (pendingLength - at - 4 < padded) {
                if (4 + padded > PENDING) die("a message longer than the stream allows", 6);
                break;
            }
            /* its own copy: what the dll is handed it may keep, or write to */
            memcpy(message, pending + at + 4, length);
            if (setMidiMsg(message, length)) {
                if (failed++ < 16) {
                    puts_("m5live: not taken");
                    for (i = 0; i < length && i < 11; i++) {
                        puts_(" ");
                        puth_(message[i]);
                    }
                    puts_(", ");
                    puti_((int) length);
                    puts_(" bytes\n");
                }
            }
            messages++;
            at += 4 + padded;
        }
        if (at) {
            for (i = 0; i < pendingLength - at; i++) {
                pending[i] = pending[at + i];
            }
            pendingLength -= at;
        }
        if (!read && !stopped) {
            /* nothing new: the dll plays on its own thread, and this has nothing else to do */
            Sleep(1);
        }
    }

    emuTerminate();
    FreeLibrary(dll);
    CloseHandle(file);

    puts_("m5live: status 0, messages ");
    puti_((int) messages);
    puts_(", not taken ");
    puti_((int) failed);
    puts_("\n");
    ExitProcess(0);
}
