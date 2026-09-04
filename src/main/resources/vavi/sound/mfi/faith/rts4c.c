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
 * Usage: rts4c <in.rt4>            a song, whose every note is known before the first is played
 *        rts4c -live <live.rt4>    a synthesizer, played by whatever turns up while it runs
 *
 * <b>The song.</b> The input is what vavi.sound.mfi.faith.FaithType4Renderer writes, all little
 * endian:
 *
 *   'R' 'T' '4' 0
 *   u32 mode       which voice set, 0..2
 *   u32 frames     how many 44100Hz stereo frames to render
 *   u32 gain       what a sample is multiplied by before it is cut to 16 bits, times 256
 *   u32 count      how many events follow
 *   count events, each  u32 frame; u32 length; length bytes, padded out to a multiple of four
 *
 * <b>The synthesizer.</b> -live is the same dll with the sequencer taken out: nobody knows what
 * is coming, so an event has no frame to fall on - it is played at the next block after it
 * arrives, and the arriving is the timing. The file it arrives in is one the host keeps
 * appending to while this reads it, which is the only channel into the emulated PC there is:
 *
 *   'R' 'T' '4' 'L'
 *   u32 mode       which voice set, 0..2
 *   u32 gain       as above
 *   u32 blocks     dll blocks to a waveOut buffer, which is what an event's timing is rounded to
 *   u32 buffers    waveOut buffers in flight, which is how much of the machine's jitter is hidden
 *   events, each  u32 length; length bytes, padded out to a multiple of four
 *
 * A length of 0xffffffff is the host saying there will be no more, and is how this ends. A
 * record only half written when it is read is left where it is until the rest of it turns up, so
 * the host may append whenever it likes without a lock.
 *
 * An event is a midi message either way. A channel message goes in as it stands; one that begins
 * 0xf0 goes through the dll's own exclusive door, which is what a UCS voice would arrive by.
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

/* what -live may ask for instead, since there the buffer is the latency rather than the safety */
#define MAXBLOCKS   64
#define MAXBUFFERS  8

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

/** the whole of what was asked for, however many reads that takes */
static int readFully(HANDLE file, unsigned char *buffer, unsigned length) {
    unsigned got = 0;
    while (got < length) {
        DWORD read = 0;
        if (!ReadFile(file, buffer + got, length - got, &read, NULL) || read == 0) {
            return 0;
        }
        got += read;
    }
    return 1;
}

static int streq(const char *a, const char *b) {
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
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

/** the dll, opened, with a slot of its own already taken out */
typedef struct {
    HMODULE        dll;
    RTPSYNTHCLOSE  closeSynth;
    RTP           *rtp;
    SYNTH         *synth;
    int            slot;
} PLAYER;

/** Loads the dll, opens the voice set asked for and takes a slot to play into. */
static void openPlayer(PLAYER *player, unsigned mode) {
    RTPSYNTHOPEN open;
    int m = (int) mode;

    player->dll = LoadLibraryA("rt_synth_4.dll");
    if (!player->dll) die("no rt_synth_4.dll beside me", 3);
    open = (RTPSYNTHOPEN) GetProcAddress(player->dll, "RTPSynthOpen");
    player->closeSynth = (RTPSYNTHCLOSE) GetProcAddress(player->dll, "RTPSynthClose");
    if (!open || !player->closeSynth) die("rt_synth_4.dll is not the Type 4 synthesizer", 3);

    player->rtp = open(&m);
    if (!player->rtp || !player->rtp->synth) die("RTPSynthOpen would not open", 7);
    player->synth = player->rtp->synth;
    player->slot = player->synth->openChannel(player->synth, 0);
    if (player->slot < 0) die("the synthesizer has no free slot", 7);
}

static void closePlayer(PLAYER *player) {
    player->synth->closeChannel(player->synth, player->slot);
    player->closeSynth(player->rtp);
    FreeLibrary(player->dll);
}

/** Opens waveOut and hands each slot a buffer of {@code blocks} of the dll's blocks. */
static HWAVEOUT openWave(SLOT *slots, unsigned buffers, unsigned blocks) {
    HWAVEOUT wave = NULL;
    WAVEFORMATEX format;
    unsigned i;

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
    memset(slots, 0, sizeof(SLOT) * MAXBUFFERS);
    for (i = 0; i < buffers; i++) {
        slots[i].samples = (short *) alloc(blocks * SAMPLES * sizeof(short));
        slots[i].header.lpData = (LPSTR) slots[i].samples;
        slots[i].header.dwBufferLength = blocks * SAMPLES * sizeof(short);
        if (waveOutPrepareHeader(wave, &slots[i].header, sizeof(WAVEHDR)) != MMSYSERR_NOERROR) {
            die("waveOut would not take a buffer", 8);
        }
    }
    return wave;
}

/** Waits for the device to be done with every buffer, and shuts it. */
static void closeWave(HWAVEOUT wave, SLOT *slots, unsigned buffers) {
    unsigned i;
    for (i = 0; i < buffers; i++) {
        while (!isFree(&slots[i])) {
            Sleep(1);
        }
        waveOutUnprepareHeader(wave, &slots[i].header, sizeof(WAVEHDR));
    }
    waveOutClose(wave);
}

/** one midi message, down whichever of the dll's two doors it belongs to */
static void apply(PLAYER *player, const unsigned char *data, unsigned length) {
    unsigned char message[4];
    if (!length) {
        return;
    }
    if (*data == 0xf0) {
        player->synth->exclusive(player->synth, player->slot, data, (int) length);
    } else {
        message[0] = data[0];
        message[1] = length > 1 ? data[1] : 0;
        message[2] = length > 2 ? data[2] : 0;
        message[3] = 0;
        player->synth->send(player->synth, player->slot, message, 1);
    }
}

/*
 * The song: every note is known before the first is played.
 */
static unsigned song(unsigned char *input, DWORD size) {
    PLAYER player;
    SLOT slots[MAXBUFFERS];
    HWAVEOUT wave;
    const unsigned char *end = input + size;
    unsigned char *event = input + 20;
    unsigned mode = le32(input + 4), frames = le32(input + 8);
    unsigned gain = le32(input + 12), count = le32(input + 16);
    unsigned rendered = 0, left = count, announced = 0;
    int block[SAMPLES];
    int i, next = 0;

    /* the dll renders a block at a time and nothing shorter, so the song is as long as that */
    frames = (frames + FRAMES - 1) / FRAMES * FRAMES;

    openPlayer(&player, mode);
    puts_("rts4c: voices ");
    puti_(player.synth->voices);
    puts_(", channels ");
    puti_(player.synth->channels(player.synth));
    puts_(", events ");
    puti_((int) count);
    puts_(", frames ");
    puti_((int) frames);
    puts_("\n");

    wave = openWave(slots, BUFFERS, BLOCKS);

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
                apply(&player, data, length);
                event = data + ((length + 3) & ~3u);
                left--;
            }

            /* Render() adds into the buffer rather than filling it, so it starts empty */
            memset(block, 0, sizeof block);
            if (player.synth->render(player.synth, block) != 0) die("Render would not render", 7);
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
    closeWave(wave, slots, BUFFERS);
    closePlayer(&player);
    return rendered;
}

/*
 * The synthesizer: nobody knows what is coming.
 *
 * The host keeps appending to a file this keeps reading, which is the one way into the emulated
 * PC that does not need the machine to have a network or a pipe. Between one block and the next
 * whatever has turned up is played, so an event's timing is how long the host took to write it
 * plus however much of a waveOut buffer is left to render - which is why the buffers here are
 * small where the song's are large. The song wants the fewest interruptions it can have; this
 * wants the shortest wait between a key going down and the sound of it.
 */

/** how much of the growing file to ask for at a poll; a midi stream is nothing like this fast */
#define POLL        1024

/** what the unparsed tail of the file may grow to while the rest of a record is awaited */
#define PENDING     65536

/** the longest single message, which is a UCS voice rather than anything a key press makes */
#define MAXMESSAGE  4096

/** the host saying there will be no more */
#define STOP        0xffffffffu

static unsigned live(HANDLE file, const unsigned char *header) {
    PLAYER player;
    SLOT slots[MAXBUFFERS];
    HWAVEOUT wave;
    unsigned mode = le32(header + 4), gain = le32(header + 8);
    unsigned blocks = le32(header + 12), buffers = le32(header + 16);
    unsigned char *pending;
    unsigned pendingLength = 0, rendered = 0, announced = 0;
    int stopped = 0, block[SAMPLES];
    unsigned i, next = 0;

    if (blocks < 1 || blocks > MAXBLOCKS) die("that many blocks to a buffer is not a latency", 6);
    if (buffers < 2 || buffers > MAXBUFFERS) die("that many buffers is not a latency", 6);
    pending = (unsigned char *) alloc(PENDING);

    openPlayer(&player, mode);
    puts_("rts4c: live, voices ");
    puti_(player.synth->voices);
    puts_(", channels ");
    puti_(player.synth->channels(player.synth));
    puts_(", blocks ");
    puti_((int) blocks);
    puts_(", buffers ");
    puti_((int) buffers);
    puts_("\n");

    wave = openWave(slots, buffers, blocks);

    while (!stopped) {
        SLOT *s = &slots[next];
        unsigned filled;

        /* the same clock as the song's: the device is as far ahead as the host has let it get */
        while (!isFree(s)) {
            Sleep(1);
        }

        for (filled = 0; filled < blocks; filled++) {
            /* whatever has turned up since the last block, played at this one */
            DWORD read = 0;
            unsigned at = 0;
            if (pendingLength < PENDING) {
                unsigned want = PENDING - pendingLength;
                if (!ReadFile(file, pending + pendingLength, want < POLL ? want : POLL,
                              &read, NULL)) {
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
                    break;
                }
                apply(&player, pending + at + 4, length);
                at += 4 + padded;
            }
            if (at) {
                for (i = 0; i < pendingLength - at; i++) {
                    pending[i] = pending[at + i];
                }
                pendingLength -= at;
            }
            if (stopped) {
                break;
            }

            /* Render() adds into the buffer rather than filling it, so it starts empty */
            memset(block, 0, sizeof block);
            if (player.synth->render(player.synth, block) != 0) die("Render would not render", 7);
            for (i = 0; i < SAMPLES; i++) {
                s->samples[filled * SAMPLES + i] = cut(block[i], (int) gain);
            }
            /*
             * One count in the first frame, which is 90dB under anything and inaudible.
             *
             * The emulated sound card drops a buffer that is all zeroes rather than playing it,
             * and a device that is dropping buffers is not being waited for - so a synthesizer
             * nobody has played yet would run flat out, and the first note would arrive at a
             * pipeline that has not settled. This is the least that can be put in a buffer to
             * make it a buffer, and from here on the card is paced and the latency is what it
             * will stay.
             */
            if (!rendered) {
                s->samples[0] = 1;
                s->samples[1] = 1;
            }
            rendered += FRAMES;
        }

        if (filled) {
            s->header.dwBufferLength = filled * SAMPLES * sizeof(short);
            if (waveOutWrite(wave, &s->header, sizeof(WAVEHDR)) != MMSYSERR_NOERROR) {
                die("waveOut would not take what was played", 8);
            }
            next = (next + 1) % buffers;
        }

        /* a synthesizer is open for as long as it is wanted, so this is once a minute */
        if (rendered / (44100u * 60) != announced) {
            announced = rendered / (44100u * 60);
            puts_("rts4c: live ");
            puti_((int) announced);
            puts_("m\n");
        }
    }

    closeWave(wave, slots, buffers);
    closePlayer(&player);
    CloseHandle(file);
    return rendered;
}

void entry(void) {
    char *argv[8];
    int argc;
    unsigned char *input;
    unsigned rendered;
    DWORD size = 0;

    out = GetStdHandle(STD_OUTPUT_HANDLE);
    argc = split(GetCommandLineA(), argv, 8);
    if (argc < 2) die("usage: rts4c [-live] <in.rt4>", 2);

    if (streq(argv[1], "-live")) {
        HANDLE file;
        unsigned char header[20];
        if (argc < 3) die("usage: rts4c -live <live.rt4>", 2);
        /*
         * FILE_SHARE_WRITE because the host has this open and is still writing to it - that is
         * the whole idea - and read to the end of what is there rather than to the end of the
         * file, since the file has no end while this runs.
         */
        file = CreateFileA(argv[2], GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        if (file == INVALID_HANDLE_VALUE) die("cannot read the events", 5);
        if (!readFully(file, header, sizeof header)) die("the events end early", 5);
        if (header[0] != 'R' || header[1] != 'T' || header[2] != '4' || header[3] != 'L') {
            die("that is not a live rt4 stream", 5);
        }
        rendered = live(file, header);
    } else {
        input = slurp(argv[1], &size);
        if (size < 20 || input[0] != 'R' || input[1] != 'T' || input[2] != '4' || input[3] != 0) {
            die("that is not an rt4 event file", 5);
        }
        rendered = song(input, size);
    }

    puts_("rts4c: status 0, frames ");
    puti_((int) rendered);
    puts_("\n");
    ExitProcess(0);
}
