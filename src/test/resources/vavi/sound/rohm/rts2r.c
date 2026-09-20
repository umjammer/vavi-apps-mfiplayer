/*
 * rts2r - renders rt_synth_2.dll, the Type 2 (rohm) synthesizer of Faith's "Ring Tone Authoring
 * Tool", into a file, which is what vavi.sound.rohm is compared with.
 *
 * Unlike rt_synth_4.dll, RTPSynthOpen of this one hands back the synthesizer itself, not a pair
 * of it and a voice set, and the synthesizer has an entry more (0x2c, the reverb). Render()
 * writes 128 frames at 44100Hz, stereo, 32 bit ints of 16 bit range, and takes what was sent
 * before it at the end of the next block.
 *
 *   i686-w64-mingw32-gcc -O2 -o rts2r.exe rts2r.c
 *   wine rts2r.exe in.ev out.raw        (rt_synth_2.dll beside it, or RTDLL=<path>)
 *
 * in.ev, little endian:
 *
 *   u32 blocks
 *   events, each  u32 block; u32 kind; u32 length; length bytes, padded out to a multiple of four
 *
 *   kind 0  a channel message, sent before the block
 *        1  an exclusive, f0 .. f7
 *        2  a parameter: u32 what (0x10001 UCS pcm, 0x10002 UCS wave headers, 0x10003 UCS zones),
 *           4 bytes of the argument after its pointer (0x10001: u16 length, u16 offset,
 *           the others: u8 length, u8 offset), then the bytes the pointer points to
 *        3  the reverb preset, a byte
 *
 * out.raw is what Render() wrote, block after block. VOICE=<n> prints the registers of a voice
 * after each block to stderr.
 *
 * @author Naohide Sano
 */
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct SYNTH SYNTH;
struct SYNTH {
    void *work;                                                         /* 0x00 */
    int voices;                                                         /* 0x04 64 */
    int slots;                                                          /* 0x08 1 */
    int (*close)(SYNTH *);                                              /* 0x0c */
    int (*render)(SYNTH *, int *out);                                   /* 0x10 128 frames */
    int (*channels)(SYNTH *);                                           /* 0x14 */
    int (*openChannel)(SYNTH *, int type);                              /* 0x18 resets the voices */
    int (*closeChannel)(SYNTH *, int slot);                             /* 0x1c */
    int (*send)(SYNTH *, int slot, const void *events, int count);      /* 0x20 4 bytes each */
    int (*exclusive)(SYNTH *, int slot, const void *data, int length);  /* 0x24 f0 .. f7 */
    int (*param)(SYNTH *, int slot, int what, void *argument);          /* 0x28 UCS */
    int (*effect)(SYNTH *, int slot, int zero, unsigned char *preset, int one); /* 0x2c reverb 0 ~ 7 */
};

typedef SYNTH *(*RTPSYNTHOPEN)(int *mode);

int main(int argc, char **argv) {
    HMODULE dll;
    RTPSYNTHOPEN open;
    SYNTH *s;
    FILE *f, *out;
    long n;
    unsigned char *in, *p, *end;
    unsigned blocks, b;
    int mode = 0, slot, block[256];

    if (argc < 3) {
        fprintf(stderr, "usage: rts2r in.ev out.raw\n");
        return 1;
    }
    dll = LoadLibraryA(getenv("RTDLL") ? getenv("RTDLL") : "rt_synth_2.dll");
    if (!dll) {
        fprintf(stderr, "no rt_synth_2.dll\n");
        return 1;
    }
    open = (RTPSYNTHOPEN) GetProcAddress(dll, "RTPSynthOpen");
    s = open(&mode);
    slot = s->openChannel(s, 0);

    f = fopen(argv[1], "rb");
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    fseek(f, 0, SEEK_SET);
    in = malloc(n);
    fread(in, 1, n, f);
    fclose(f);
    blocks = *(unsigned *) in;
    p = in + 4;
    end = in + n;

    out = fopen(argv[2], "wb");
    for (b = 0; b < blocks; b++) {
        while (p < end && *(unsigned *) p == b) {
            unsigned kind = ((unsigned *) p)[1], length = ((unsigned *) p)[2];
            unsigned char *data = p + 12;
            if (kind == 0) {
                unsigned char message[4] = { data[0], length > 1 ? data[1] : 0, length > 2 ? data[2] : 0, 0 };
                s->send(s, slot, message, 1);
            } else if (kind == 1) {
                s->exclusive(s, slot, data, length);
            } else if (kind == 2) {
                struct { void *pointer; unsigned char rest[4]; } argument;
                argument.pointer = data + 8;
                memcpy(argument.rest, data + 4, 4);
                s->param(s, slot, *(int *) data, &argument);
            } else if (kind == 3) {
                unsigned char preset = data[0];
                s->effect(s, slot, 0, &preset, 1);
            }
            p += 12 + ((length + 3) & ~3);
        }
        memset(block, 0, sizeof block);
        s->render(s, block);
        fwrite(block, 4, 256, out);
        if (getenv("VOICE")) {
            int v = atoi(getenv("VOICE")), i;
            short *r = (short *) ((char *) dll + 0x46498 + v * 0x60);
            fprintf(stderr, "%u", b);
            for (i = 0; i < 0x30; i++) fprintf(stderr, " %d", r[i]);
            fprintf(stderr, "\n");
        }
    }
    fclose(out);
    return 0;
}
