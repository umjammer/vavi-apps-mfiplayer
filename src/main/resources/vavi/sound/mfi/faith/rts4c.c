/*
 * rts4c - a console front end for rt_synth_4.dll, the Type 4 synthesizer of Faith's
 * "Ring Tone Authoring Tool" - the fuetrek voice engine that was in an MFi phone.
 *
 * The dll is a synthesizer and nothing else. It has no file format, no sequencer and no clock,
 * and nothing in the toolkit will play a song: RTPlayer.exe does it from its own gui and that is
 * the whole of what is on offer. So what this is, is the sequencer the dll has not got - it is
 * handed the notes with the sample each one falls on, and it turns the crank a block at a time.
 *
 * <b>It plays, it does not render.</b> What comes out goes to the emulated PC's own waveOut
 * device, block by block, and the host takes it from there - see FaithType4Player. That is what
 * makes a song start in a second rather than when the last bar of it has been synthesized, and
 * it is also what keeps time: waveOut hands a buffer back only once the host has taken the last
 * one, so a machine that would otherwise run flat out is held to the rate the song is heard at.
 *
 * <b>The api.</b> Three exports, of which two matter. RTPSynthOpen(&mode) hands back a pair of
 * pointers - the synthesizer and the voice set under it - and the synthesizer is not a c++
 * object but a struct of plain cdecl function pointers, written into it one by one by the
 * constructor at 0x100013b0: see SYNTH below, which is that struct. Render() adds 128 stereo
 * frames into a buffer the caller owns and clears; the dll runs at 32000Hz and resamples on the
 * way out, which is why 44100 is not a choice here. Voices are 48, or 64 in the third voice set.
 *
 * It is built without a C runtime - kernel32 and nothing else - because the emulated PC it runs
 * on has kernel32 and not the runtime a modern mingw links against by default (the ucrt's
 * api-ms-win-crt-* stubs), and because there is nothing here worth a runtime.
 *
 *   i686-w64-mingw32-gcc -O2 -nostdlib -ffreestanding -fno-builtin -e _entry \
 *       -Wl,--subsystem,console -o rts4c.exe rts4c.c -lkernel32 -lwinmm
 *
 * Usage: rts4c <in.rt4>
 *
 * The input is what vavi.sound.mfi.faith.FaithType4Renderer writes, all little endian:
 *
 *   'R' 'T' '4' 0
 *   u32 mode       which voice set, 0..2
 *   u32 frames     how many 44100Hz stereo frames to render
 *   u32 gain       what a sample is multiplied by before it is cut to 16 bits, times 256
 *   u32 count      how many events follow
 *   count events, each  u32 frame; u32 length; length bytes, padded out to a multiple of four
 *
 * An event is a midi message. A channel message goes in as it stands; one that begins 0xf0 goes
 * through the dll's own exclusive door, which is what a UCS voice would arrive by.
 *
 * The output is 44100Hz 16 bit stereo, and it goes to waveOut, not to a file.
 *
 * @author Naohide Sano
 */
#include <windows.h>

/* frames one Render() call produces, which is not a choice - the dll writes exactly this much */
#define FRAMES   128
#define SAMPLES  (FRAMES * 2)

/* how many of the dll's blocks make up one waveOut buffer - about 46ms of audio */
#define BLOCKS   16

/* how many of those are in flight at once; the host's queue is where the real cushion is */
#define BUFFERS  4

/*
 * What RTPSynthOpen builds, laid out as it lays it out.
 *
 * Not a c++ object with a vtable - the pointers sit in the instance itself - so these are plain
 * cdecl calls that happen to take it as their first argument.
 */
typedef struct SYNTH SYNTH;
struct SYNTH {
    void *work;                                     /* 0x00 the 0x440 bytes it plays out of */
    int   voices;                                   /* 0x04 48, or 64 in the third voice set */
    int   slots;                                    /* 0x08 how many songs at once, which is 4 */
    int (*close)(SYNTH *);                          /* 0x0c */
    int (*render)(SYNTH *, int *out);               /* 0x10 adds SAMPLES ints into out */
    int (*channels)(SYNTH *);                       /* 0x14 2 */
    int (*openChannel)(SYNTH *, int type);          /* 0x18 -> slot, or -1 */
    int (*closeChannel)(SYNTH *, int slot);         /* 0x1c */
    int (*send)(SYNTH *, int slot, const void *events, int count);       /* 0x20 4 bytes each */
    int (*exclusive)(SYNTH *, int slot, const void *data, int length);   /* 0x24 f0 .. f7 */
    int (*param)(SYNTH *, int slot, int a, int b);                       /* 0x28 */
};

/** what the dll hands back, which owns the synthesizer and the voice set under it */
typedef struct {
    SYNTH *synth;
    void  *device;
} RTP;

typedef RTP *(*RTPSYNTHOPEN)(int *mode);
typedef void (*RTPSYNTHCLOSE)(RTP *);

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

/** says what went wrong on stdout and goes, so the caller has more than an exit code to go on */
static void die(const char *what, int code) {
    puts_("rts4c: error ");
    puts_(what);
    puts_("\n");
    ExitProcess(code);
}

/**
 * The command line, split on spaces with "quotes" honoured, taken from GetCommandLineA.
 *
 * Not GetCommandLineW and not CommandLineToArgvW: the emulated PC has neither in a state a
 * program can use - its GetCommandLineW hands back an ANSI string in a buffer sized for one -
 * and the drive under all this carries ascii names only, so a byte is a character here.
 */
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

/** the whole of a file, and how big it was */
static unsigned char *slurp(const char *path, DWORD *size) {
    unsigned char *data;
    DWORD read = 0;
    HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) die("cannot read the events", 5);
    *size = GetFileSize(f, NULL);
    data = (unsigned char *) alloc(*size ? *size : 1);
    if (!ReadFile(f, data, *size, &read, NULL) || read != *size) die("the events end early", 5);
    CloseHandle(f);
    return data;
}

static unsigned le32(const unsigned char *p) {
    return p[0] | ((unsigned) p[1] << 8) | ((unsigned) p[2] << 16) | ((unsigned) p[3] << 24);
}

/**
 * A rendered sample, at the volume asked for and cut down to sixteen bits.
 *
 * The gain is an integer - the real thing times 256 - rather than a float because there is no
 * runtime here to convert one, and the sample is held down to where the multiply cannot
 * overflow before it happens rather than after.
 */
static short cut(int sample, int gain) {
    int v;
    if (sample > 2000000) sample = 2000000;
    if (sample < -2000000) sample = -2000000;
    v = (sample * gain) >> 8;
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return (short) v;
}

/** one waveOut buffer and the samples it carries */
typedef struct {
    WAVEHDR header;
    short  *samples;
} SLOT;

/** has the device handed this one back? */
static int isFree(const SLOT *slot) {
    return (slot->header.dwFlags & WHDR_INQUEUE) == 0;
}

void entry(void) {
    char *argv[8];
    int argc;
    unsigned char *input, *event;
    const unsigned char *end;
    DWORD size = 0;
    unsigned mode, frames, gain, count, rendered = 0, left, announced = 0;
    HMODULE dll;
    RTPSYNTHOPEN open;
    RTPSYNTHCLOSE closeSynth;
    RTP *rtp;
    SYNTH *synth;
    int slot, m, i, next = 0;
    int block[SAMPLES];
    unsigned char message[4];
    HWAVEOUT wave = NULL;
    WAVEFORMATEX format;
    SLOT slots[BUFFERS];

    out = GetStdHandle(STD_OUTPUT_HANDLE);
    argc = split(GetCommandLineA(), argv, 8);
    if (argc < 2) die("usage: rts4c <in.rt4>", 2);

    input = slurp(argv[1], &size);
    if (size < 20 || input[0] != 'R' || input[1] != 'T' || input[2] != '4' || input[3] != 0) {
        die("that is not an rt4 event file", 5);
    }
    mode = le32(input + 4);
    frames = le32(input + 8);
    gain = le32(input + 12);
    count = le32(input + 16);
    event = input + 20;
    end = input + size;
    left = count;
    /* the dll renders a block at a time and nothing shorter, so the song is as long as that */
    frames = (frames + FRAMES - 1) / FRAMES * FRAMES;

    dll = LoadLibraryA("rt_synth_4.dll");
    if (!dll) die("no rt_synth_4.dll beside me", 3);
    open = (RTPSYNTHOPEN) GetProcAddress(dll, "RTPSynthOpen");
    closeSynth = (RTPSYNTHCLOSE) GetProcAddress(dll, "RTPSynthClose");
    if (!open || !closeSynth) die("rt_synth_4.dll is not the Type 4 synthesizer", 3);

    m = (int) mode;
    rtp = open(&m);
    if (!rtp || !rtp->synth) die("RTPSynthOpen would not open", 7);
    synth = rtp->synth;
    puts_("rts4c: voices ");
    puti_(synth->voices);
    puts_(", channels ");
    puti_(synth->channels(synth));
    puts_(", events ");
    puti_((int) count);
    puts_(", frames ");
    puti_((int) frames);
    puts_("\n");

    slot = synth->openChannel(synth, 0);
    if (slot < 0) die("the synthesizer has no free slot", 7);

    /* 44100Hz sixteen bit stereo, which is what the dll's own resampler produces */
    memset(&format, 0, sizeof format);
    format.wFormatTag = WAVE_FORMAT_PCM;
    format.nChannels = 2;
    format.nSamplesPerSec = 44100;
    format.wBitsPerSample = 16;
    format.nBlockAlign = 4;
    format.nAvgBytesPerSec = 44100 * 4;
    if (waveOutOpen(&wave, WAVE_MAPPER, &format, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR) {
        die("no waveOut device to play on", 8);
    }
    memset(slots, 0, sizeof slots);
    for (i = 0; i < BUFFERS; i++) {
        slots[i].samples = (short *) alloc(BLOCKS * SAMPLES * sizeof(short));
        slots[i].header.lpData = (LPSTR) slots[i].samples;
        slots[i].header.dwBufferLength = BLOCKS * SAMPLES * sizeof(short);
        if (waveOutPrepareHeader(wave, &slots[i].header, sizeof(WAVEHDR)) != MMSYSERR_NOERROR) {
            die("waveOut would not take a buffer", 8);
        }
    }

    while (rendered < frames) {
        SLOT *s = &slots[next];
        int filled;

        /*
         * Waiting here is the clock. The device hands a buffer back when the host has taken the
         * one before it, so a machine that could synthesize this song four times over is held to
         * playing it once, and nothing here has to know what the time is.
         */
        while (!isFree(s)) {
            Sleep(1);
        }

        for (filled = 0; filled < BLOCKS && rendered < frames; filled++) {
            /* everything that has come due, in the order it was written down */
            while (left && event + 8 <= end && le32(event) <= rendered) {
                unsigned length = le32(event + 4);
                unsigned char *data = event + 8;
                if (data + length > end) die("an event runs off the end", 5);
                if (length && *data == 0xf0) {
                    synth->exclusive(synth, slot, data, (int) length);
                } else if (length) {
                    message[0] = data[0];
                    message[1] = length > 1 ? data[1] : 0;
                    message[2] = length > 2 ? data[2] : 0;
                    message[3] = 0;
                    synth->send(synth, slot, message, 1);
                }
                event = data + ((length + 3) & ~3u);
                left--;
            }

            /* Render() adds into the buffer rather than filling it, so it starts empty */
            memset(block, 0, sizeof block);
            if (synth->render(synth, block) != 0) die("Render would not render", 7);
            for (i = 0; i < SAMPLES; i++) {
                s->samples[filled * SAMPLES + i] = cut(block[i], (int) gain);
            }
            rendered += FRAMES;
        }

        s->header.dwBufferLength = filled * SAMPLES * sizeof(short);
        if (waveOutWrite(wave, &s->header, sizeof(WAVEHDR)) != MMSYSERR_NOERROR) {
            die("waveOut would not take what was played", 8);
        }
        next = (next + 1) % BUFFERS;

        /* something for the caller to see it is still alive by */
        if (rendered / 441000 != announced) {
            announced = rendered / 441000;
            puts_("rts4c: played ");
            puti_((int) (announced * 10));
            puts_("s\n");
        }
    }

    /* the last buffers are still with the device, and the song is not over until they are not */
    for (i = 0; i < BUFFERS; i++) {
        while (!isFree(&slots[i])) {
            Sleep(1);
        }
        waveOutUnprepareHeader(wave, &slots[i].header, sizeof(WAVEHDR));
    }
    waveOutClose(wave);

    synth->closeChannel(synth, slot);
    closeSynth(rtp);
    FreeLibrary(dll);

    puts_("rts4c: status 0, frames ");
    puti_((int) rendered);
    puts_("\n");
    ExitProcess(0);
}
