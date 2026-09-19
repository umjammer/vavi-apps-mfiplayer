/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;

import static java.lang.System.getLogger;


/**
 * The effects of the dsp (ARM::CDsp2): the reverb and the chorus of the dsp program the driver
 * writes at its initialization.
 * <p>
 * The library does not run a dsp program as it is: it knows programs by what they are, and runs
 * code of its own for the one it knows (six parts, a variant each), the coefficients the program
 * takes going through maps of the addresses to where that code reads them. This knows the one
 * program the driver writes ({@link #PROGRAM}, the variants 0, 2, 3, 9, 2, 0 and the maps of them,
 * as the library makes them), and nothing else: any other program leaves the effects off.
 * <p>
 * {@link #procDsp2()} is the library's {@code CDsp2::ProcDsp2} of those variants, translated
 * instruction by instruction from the arm64 code the library executes for them, on a memory of
 * the same layout as the object ({@link #OBJECT}), so what it keeps is where the library keeps it.
 * A branch to code which is not translated throws, and the effects go off.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
final class Ma7Dsp2 {

    private static final Logger logger = getLogger(Ma7Dsp2.class.getName());

    /** the size of ARM::CDsp2 */
    static final int SIZE = 0x36fb58;

    /** the dsp program the driver writes, 768 words of 6 bytes, in the library */
    static final int PROGRAM = 0x439870;

    // the address space of the translated code

    /** the object */
    private static final long OBJECT = 0x1000_0000L;
    /** ARM::CDsp, its fields at + 0x3bf7a0 ~ */
    private static final long CDSP = 0x2000_0000L;
    private static final long CDSP_FIELDS = CDSP + 0x3bf7a0;
    /** the buffers of a block (ARM::_genbuf), 13 pointers */
    private static final long GENBUF = 0x3000_0000L;
    /** the buffers, 1 MB apart */
    private static final long BUS = 0x4000_0000L;
    /** the top of the stack */
    private static final long STACK = 0x5000_1000L;

    /** the object, little endian words */
    final int[] o = new int[SIZE / 4];
    /** CDsp + 0x3bf7a0: the buffers (8), the sample of them (4), ..., + 0x40 the number of outputs */
    private final int[] cdsp = new int[0x48 / 4];
    private final int[] stack = new int[0x400 / 4];
    private int[][] bus;

    /** the default program recognized */
    private final int[] program;

    /** CDsp + 0x3a302c, DSPCONTROL_ResetDsp's */
    private int programState;
    /** CDsp + 0x3a3030, bit 7 of the intermediate register 0x30 */
    private int resetState;

    Ma7Dsp2(Ma7Rom rom) {
        program = new int[768 * 2];
        for (int i = 0; i < 768; i++) {
            int b = PROGRAM + i * 6;
            program[i * 2] = rom.u8(b) << 8 | rom.u8(b + 1);
            program[i * 2 + 1] = rom.u8(b + 2) << 24 | rom.u8(b + 3) << 16 | rom.u8(b + 4) << 8 | rom.u8(b + 5);
        }
        // CDsp2::CDsp2, Init
        st64(OBJECT, CDSP);
        programReset();
        reset();
    }

    // ---- memory

    private int ld32(long a) {
        long r = a - OBJECT;
        if (r >= 0 && r < SIZE) return o[(int) (r >> 2)];
        r = a - BUS;
        if (r >= 0 && r < 13L << 20) return bus[(int) (r >> 20)][(int) (r & 0xfffff) >> 2];
        r = a - (STACK - 0x400);
        if (r >= 0 && r < 0x400) return stack[(int) (r >> 2)];
        r = a - CDSP_FIELDS;
        if (r >= 0 && r < 0x48) return cdsp[(int) (r >> 2)];
        r = a - GENBUF;
        if (r >= 0 && r < 13 * 8) {
            long p = BUS + (r >> 3 << 20);
            return (r & 4) == 0 ? (int) p : (int) (p >>> 32);
        }
        throw new IllegalStateException("ld32: 0x" + Long.toHexString(a));
    }

    private long ld64(long a) {
        return (ld32(a) & 0xffffffffL) | (long) ld32(a + 4) << 32;
    }

    private int ld16(long a) {
        int w = ld32(a & ~3L);
        return (a & 2) == 0 ? w & 0xffff : w >>> 16;
    }

    private void st32(long a, int v) {
        long r = a - OBJECT;
        if (r >= 0 && r < SIZE) { o[(int) (r >> 2)] = v; return; }
        r = a - BUS;
        if (r >= 0 && r < 13L << 20) { bus[(int) (r >> 20)][(int) (r & 0xfffff) >> 2] = v; return; }
        r = a - (STACK - 0x400);
        if (r >= 0 && r < 0x400) { stack[(int) (r >> 2)] = v; return; }
        r = a - CDSP_FIELDS;
        if (r >= 0 && r < 0x48) { cdsp[(int) (r >> 2)] = v; return; }
        throw new IllegalStateException("st32: 0x" + Long.toHexString(a));
    }

    private void st64(long a, long v) {
        st32(a, (int) v);
        st32(a + 4, (int) (v >>> 32));
    }

    private void st16(long a, int v) {
        int w = ld32(a & ~3L);
        st32(a & ~3L, (a & 2) == 0 ? (w & 0xffff0000) | (v & 0xffff) : (w & 0xffff) | v << 16);
    }

    private void st8(long a, long v) {
        int w = ld32(a & ~3L), sh = (int) (a & 3) * 8;
        st32(a & ~3L, (w & ~(0xff << sh)) | ((int) v & 0xff) << sh);
    }

    private int ld8(long a) {
        return ld32(a & ~3L) >>> ((int) (a & 3) * 8) & 0xff;
    }

    private void memset(long a, long c, long n) {
        for (long i = 0; i < n; i++) st8(a + i, c);
    }

    private void memcpy(long d, long s, long n) {
        for (long i = 0; i < n; i++) st8(d + i, ld8(s + i));
    }

    /** zeros of the object, [from, to) */
    private void fill(int from, int to) {
        while (from < to && (from & 3) != 0) st8(OBJECT + from++, 0);
        while (to > from && (to & 3) != 0) st8(OBJECT + --to, 0);
        Arrays.fill(o, from >> 2, to >> 2, 0);
    }

    private int i32(int offset) {
        return o[offset >> 2];
    }

    private void i32(int offset, int v) {
        o[offset >> 2] = v;
    }

    private int s16(int offset) {
        return (short) ld16(OBJECT + offset);
    }

    // ---- the library's

    /** CDsp2::ProgramReset */
    void programReset() {
        st8(OBJECT + 8, 0);
        st8(OBJECT + 9, 0);
        i32(0xc, 0);
        int[] variants = { 2, 3, 4, 0xb, 2, 5 };
        for (int i = 0; i < 6; i++) {
            i32(0x1e3c + i * 4, variants[i]);
            i32(0x1e54 + i * 4, variants[i]);
        }
        i32(0x2ecad0, 0);
        i32(0x32d2d4, 0);
        for (int k = 0; k < 0x300; k++) {
            st64(OBJECT + 0x20 + k * 8, 0);
            st16(OBJECT + 0x1e6e + k * 8, 0xffff);
            st16(OBJECT + 0x7436 + k * 12, 0xffff);
        }
    }

    /** CDsp2::Reset, what it stores as the arm64 code of it does */
    void reset() {
        fill(0x10, 0x20);
        fill(0x1820, 0x1e3c);
        fill(0x366c, 0x7431);
        fill(0x9a28, 0x9a31);
        fill(0x9a34, 0x2ecad0);
        fill(0x2ecad4, 0x32d2d4);
        fill(0x32d2d8, 0x36fb58);
        st32(OBJECT + 0x1e20, 0x2000);
        st32(OBJECT + 0x1e28, 0x1);
        st32(OBJECT + 0x9a70, 0x1f);
        st32(OBJECT + 0x9a74, 0x1e);
        st32(OBJECT + 0x9a78, 0x1d);
        st32(OBJECT + 0x9a7c, 0x1c);
        st32(OBJECT + 0x9a80, 0x1b);
        st32(OBJECT + 0x9a84, 0x1a);
        st32(OBJECT + 0x9a88, 0x19);
        st32(OBJECT + 0x9a8c, 0x18);
        st32(OBJECT + 0x9a90, 0x17);
        st32(OBJECT + 0x9a94, 0x16);
        st32(OBJECT + 0x9a98, 0x15);
        st32(OBJECT + 0x9a9c, 0x14);
        st32(OBJECT + 0x9aa0, 0x13);
        st32(OBJECT + 0x9aa4, 0x12);
        st32(OBJECT + 0x9aa8, 0x11);
        st32(OBJECT + 0x9aac, 0x10);
        st32(OBJECT + 0x9ab0, 0xf);
        st32(OBJECT + 0x9ab4, 0xe);
        st32(OBJECT + 0x9ab8, 0xd);
        st32(OBJECT + 0x9abc, 0xc);
        st32(OBJECT + 0x9ac0, 0xb);
        st32(OBJECT + 0x9ac4, 0xa);
        st32(OBJECT + 0x9ac8, 0x9);
        st32(OBJECT + 0x9acc, 0x8);
    }

    /** CDsp::ResetDsp, DSPCONTROL_ResetDsp */
    void resetDsp(int v) {
        if (v > 1) return;
        if (v == 0 && programState == 1) programReset();
        programState = v;
    }

    /** CDsp2::SetRamSelect: 0 coefficients, 1 program */
    void setRamSelect(int ram) {
        i32(0x10, ram);
    }

    /** CDsp2::Start */
    void start() {
        if (i32(0x14) == 0) i32(0x14, 1);
    }

    /** CDsp2::Stop */
    void stop() {
        if (i32(0x14) == 1) i32(0x14, 0);
    }

    /** CDsp2::SetDspAdr */
    void setDspAdr(int adr) {
        if (adr >= 0x300) return;
        if (i32(0x10) == 0) i32(0x1c, adr);
        else if (i32(0x10) == 1) i32(0x18, adr);
    }

    /** CDsp2::SetArea0Size */
    void setArea0Size(int size) {
        i32(0x1e20, size);
        i32(0x1e24, 0x2000 - size);
    }

    /** CDsp2::SetArea1FsRatio */
    void setArea1FsRatio(int ratio) {
        i32(0x1e28, ratio);
    }

    /** CDsp2::SetTramMode */
    void setTramMode(int mode) {
        i32(0x1e2c, mode);
    }

    /** CDsp2::SetRewriteWindow, but of (0, 0) when a window is set */
    void setRewriteWindow(int start, int end) {
        if (start > 0x2ff || end > 0x2ff) return;
        if ((start | end) != 0) {
            i32(0x1e38, 1);
        } else if (i32(0x1e38) == 1) {
            logger.log(Level.WARNING, "dsp: rewrite window of (0, 0) is not ported");
            return;
        }
        i32(0x1e30, start);
        i32(0x1e34, end);
    }

    /** the intermediate register 0x30 (DSPCONTROL_SetDspVoiceReg) */
    void control(int data) {
        int r = data >> 7;
        if (r == 0 && resetState == 1) {
            // CDsp1::Reset does nothing, CSrc is not of 48 kHz
            reset();
            resetState = 0;
            return;
        }
        resetState = r;
        setRamSelect(data & 1);
        if ((data & 0x40) == 0) {
            stop();
        } else if (resetState == 0) {
            start();
        }
    }

    /** a coefficient of 16 bits to the form a map tells, bits moved about */
    private static int permute(int data) {
        int d = (short) data;
        int v = d < 0 ? 0x4000 : 0;
        if ((data & 0x4000) != 0) v = d < 0 ? 0xffffc000 : 0xffff8000;
        if ((data & 0x2000) != 0) v |= 8;
        if ((data & 0x1000) != 0) v |= 0x80;
        if ((data & 0x800) != 0) v |= 1;
        if ((data & 0x400) != 0) v |= 0x200;
        if ((data & 0x200) != 0) v |= 0x100;
        if ((data & 0x100) != 0) v |= 0x400;
        if ((data & 0x80) != 0) v |= 0x10;
        if ((data & 0x40) != 0) v |= 0x20;
        if ((data & 0x20) != 0) v |= 0x1000;
        if ((data & 0x10) != 0) v |= 0x2000;
        if ((data & 8) != 0) v |= 4;
        if ((data & 4) != 0) v |= 0x40;
        if ((data & 2) != 0) v |= 2;
        if ((data & 1) != 0) v |= 0x800;
        return v;
    }

    /**
     * CDsp2::SetDspData
     * @param hi the program: the upper 16 bits of a word of 48
     * @param lo the program: the lower 32 bits, the coefficients: 16 bits
     */
    void setDspData(int hi, int lo) {
        if (i32(0x10) != 0) {
            if (i32(0x10) != 1) return;
            int adr = i32(0x18);
            if (ld8(OBJECT + 0x9a30) == 0 || Integer.compareUnsigned(adr - 0x13, 0x5f) > 0) {
                st64(OBJECT + 0x20 + adr * 8L, (hi & 0xffffL) << 32 | (lo & 0xffffffffL));
            }
            if (i32(0x14) == 0 && adr == 0x2ff) recognize();
            i32(0x18, adr + 1 == 0x300 ? 0 : adr + 1);
            return;
        }
        int adr = i32(0x1c);
        int d = (short) lo;
        st16(OBJECT + 0x1820 + adr * 2L, lo);
        int m = s16(0x1e6e + adr * 8);
        if (m >= 0) {
            int v = s16(0x1e72 + adr * 8) != 0 ? permute(lo) : d;
            i32(0x496c + m * 4, v << (s16(0x1e70 + adr * 8) & 0x1f));
        }
        m = s16(0x7436 + adr * 12);
        if (m >= 0) {
            int v = s16(0x743e + adr * 12) != 0 ? permute(lo) : d;
            i32(0x98ac + m * 4, (i32(0x7438 + adr * 12) * v << (s16(0x743c + adr * 12) & 0x1f)) >> 7);
        }
        if (adr != 0) {
            i32(0x1c, adr + 1 == 0x300 ? 0 : adr + 1);
            return;
        }
        if (d != -1) {
            if (d == 0x7e00) {
                st8(OBJECT + 0x9a30, 1);
                st64(OBJECT + 0xb8, 0x19c0400001L);
                st64(OBJECT + 0xc0, 0x1ac0440001L);
                st64(OBJECT + 0xc8, 0x8000008e4000L);
                for (int i = 0xd0; i < 0xf0; i += 8) st64(OBJECT + i, 0);
            }
            i32(0x1c, 1);
            return;
        }
        // the coefficients written go at once
        st8(OBJECT + 0x9a30, 0);
        for (int i = 0; i < 6; i++) {
            if (i32(0x1e3c + i * 4) != i32(0x1e54 + i * 4) && i32(0x14) != 0) {
                logger.log(Level.WARNING, "dsp: a variant changed running, what it had is not cleared");
            }
        }
        memcpy(OBJECT + 0x366c, OBJECT + 0x496c, 0x1300);
        memcpy(OBJECT + 0x9834, OBJECT + 0x98ac, 0x78);
        memcpy(OBJECT + 0x9924, OBJECT + 0x99a6, 0x82);
        for (int i = 0; i < 6; i++) i32(0x1e3c + i * 4, i32(0x1e54 + i * 4));
        i32(0x1c, 1);
    }

    /**
     * the address map of the coefficients of {@link #PROGRAM} as the library makes it:
     * "adr:index:shift:permuted[:count]" in hex, the addresses and the indices of a run going up by 1
     */
    private static final String MAP = """
            a:0:0:0:2 73:2:0:0:3 cd:45e:0:0 ce:461:0:0 d1:d:0:0:4 d5:460:1:0 d6:45f:1:0 da:9:0:0 dd:a:0:0:2 df:463:1:0
            e0:462:1:0 e2:464:1:0 e3:13:0:0:3 e8:c:0:0 e9:16:0:0 ea:7:0:0 eb:11:0:0 ec:8:0:0 ed:12:0:0 ee:465:0:0
            ef:46f:0:0 f0:471:0:0 f1:473:0:0 f2:475:0:0 f3:467:0:0 f4:469:0:0 f5:46b:0:0 f6:46d:0:0 f7:470:0:0
            f8:472:0:0 f9:474:0:0 fa:476:0:0 fb:466:0:0 fc:468:0:0 fd:46a:0:0 fe:46c:0:0 ff:46e:0:0 100:477:0:0
            101:f5:0:0 102:126:0:0 103:157:0:0 104:188:0:0 108:1b9:0:0:2 117:479:0:0:2 119:478:0:0 11e:47b:0:0
            16c:f6:0:0:17 183:127:0:0:17 19a:158:0:0:17 1b1:189:0:0:17 1c8:10d:0:0:17 1df:13e:0:0:17 1f6:16f:0:0:17
            20d:1a0:0:0:17 224:1c3:1:1 225:1de:1:1 226:1c5:1:1 227:1c7:1:1 228:1cd:1:1 229:1cf:1:1 230:1ce:0:1
            232:1cc:0:1 233:1d0:0:1 235:1d2:1:1 236:1d4:1:1 237:1c6:0:1 239:1c4:0:1 23a:1c8:0:1 23c:1d3:0:1 23e:1d1:0:1
            23f:1d5:0:1 241:1ca:0:1 242:1c9:0:1 243:1cb:0:1 244:1d7:0:1 245:1d6:0:1 246:1d8:0:1 24a:1e0:1:1 24b:1e2:1:1
            24c:1e8:1:1 24d:1ea:1:1 254:1e9:0:1 256:1e7:0:1 257:1eb:0:1 259:1ed:1:1 25a:1ef:1:1 25b:1e1:0:1 25d:1df:0:1
            25e:1e3:0:1 260:1ee:0:1 262:1ec:0:1 263:1f0:0:1 265:1e5:0:1 266:1e4:0:1 267:1e6:0:1 268:1f2:0:1 269:1f1:0:1
            26a:1f3:0:1 26c:1d9:0:1:4 270:1f4:0:1:4 277:1dd:3:0 278:1f8:3:0 282:1c1:0:0 283:1bf:0:0 284:1bd:0:0
            285:1bb:0:0 286:5:0:0 289:1c2:0:0 28a:1c0:0:0 28b:1be:0:0 28c:1bc:0:0 28d:6:0:0 2fa:47c:0:0 2fb:47e:0:0
            2fd:47d:0:0 2fe:47f:0:0
            """;

    /** the recognition of the program at its last word, only of the one of the driver */
    private void recognize() {
        for (int i = 0; i < 768; i++) {
            long w = ld64(OBJECT + 0x20 + i * 8L);
            if ((int) (w >>> 32) != program[i * 2] || (int) w != program[i * 2 + 1]) {
                logger.log(Level.WARNING, "dsp: a program not known, the effects are off");
                i32(0xc, 0);
                return;
            }
        }
        st8(OBJECT + 8, 1);
        st8(OBJECT + 9, 1);
        i32(0xc, 1);
        int[] variants = { 0, 2, 3, 9, 2, 0 };
        for (int i = 0; i < 6; i++) i32(0x1e54 + i * 4, variants[i]);
        for (int k = 0; k < 0x300; k++) {
            st16(OBJECT + 0x1e6e + k * 8, 0xffff);
            st16(OBJECT + 0x7436 + k * 12, 0xffff);
        }
        for (String run : MAP.trim().split("\\s+")) {
            String[] f = run.split(":");
            int adr = Integer.parseInt(f[0], 16), index = Integer.parseInt(f[1], 16);
            int n = f.length > 4 ? Integer.parseInt(f[4], 16) : 1;
            for (int i = 0; i < n; i++) {
                long a = OBJECT + 0x1e6e + (adr + i) * 8L;
                st16(a, index + i);
                st16(a + 2, Integer.parseInt(f[2], 16));
                st16(a + 4, Integer.parseInt(f[3], 16));
                st16(a + 6, 0);
            }
        }
        // the second map: two runs of 6, the coefficients << 8
        for (int i = 0; i < 6; i++) {
            for (int[] r : new int[][] {{ 0x111, 0xa }, { 0x142, 0x10 }}) {
                long a = OBJECT + 0x7436 + (r[0] + i) * 12L;
                st16(a, r[1] + i);
                st32(a + 2, 0x8000);
                st16(a + 6, 0);
                st16(a + 8, 0);
            }
        }
        // the delays of the reverb
        i32(0x9a24, 0x000c000c);
    }

    // ---- rendering

    /** the effects of a block, before CDsp1::ProcDsp1 */
    void generate(int n, int[][] bus) {
        if (i32(0x14) == 0 || i32(0xc) == 0) return;
        this.bus = bus;
        for (int i = 0; i < n; i++) {
            cdsp[0] = (int) GENBUF;
            cdsp[1] = (int) (GENBUF >>> 32);
            cdsp[2] = i;
            try {
                procDsp2();
            } catch (IllegalStateException e) {
                logger.log(Level.WARNING, "dsp: " + e.getMessage() + ", the effects are off");
                i32(0xc, 0);
                return;
            }
        }
    }

    // ---- generated by a64j.py from the executed blocks of procDsp2

    private long x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16, x17, x18, x19, x20, x21, x22, x23, x24, x25, x26, x27, x28, x29, x30;
    private long v0l, v0h, v1l, v1h, v2l, v2h, v3l, v3h, v4l, v4h, v5l, v5h, v6l, v6h, v7l, v7h, v8l, v8h, v9l, v9h, v10l, v10h, v11l, v11h, v12l, v12h, v13l, v13h, v14l, v14h, v15l, v15h, v16l, v16h, v17l, v17h, v18l, v18h, v19l, v19h, v20l, v20h, v21l, v21h, v22l, v22h, v23l, v23h, v24l, v24h, v25l, v25h, v26l, v26h, v27l, v27h, v28l, v28h, v29l, v29h, v30l, v30h, v31l, v31h;
    private long sp;
    private boolean n, z, c, v;

    private void procDsp2() {
        sp = STACK;
        x0 = OBJECT;
        int pc = 0x72060;
        while (pc >= 0) {
            pc = switch (pc) {
            case 0x72060 -> b72060();
            case 0x72088 -> b72088();
            case 0x72090 -> b72090();
            case 0x720b4 -> b720b4();
            case 0x720cc -> b720cc();
            case 0x720d0 -> b720d0();
            case 0x7211c -> b7211c();
            case 0x721b4 -> b721b4();
            case 0x721f4 -> b721f4();
            case 0x72220 -> b72220();
            case 0x72240 -> b72240();
            case 0x72254 -> b72254();
            case 0x7225c -> b7225c();
            case 0x72270 -> b72270();
            case 0x72278 -> b72278();
            case 0x72284 -> b72284();
            case 0x722a0 -> b722a0();
            case 0x722bc -> b722bc();
            case 0x722d4 -> b722d4();
            case 0x722e8 -> b722e8();
            case 0x722f0 -> b722f0();
            case 0x722fc -> b722fc();
            case 0x72318 -> b72318();
            case 0x72334 -> b72334();
            case 0x7234c -> b7234c();
            case 0x72398 -> b72398();
            case 0x72400 -> b72400();
            case 0x7240c -> b7240c();
            case 0x724c0 -> b724c0();
            case 0x72500 -> b72500();
            case 0x72508 -> b72508();
            case 0x726ec -> b726ec();
            case 0x726f8 -> b726f8();
            case 0x72700 -> b72700();
            case 0x72714 -> b72714();
            case 0x72760 -> b72760();
            case 0x72764 -> b72764();
            case 0x727a0 -> b727a0();
            case 0x727a8 -> b727a8();
            case 0x727b0 -> b727b0();
            case 0x727b8 -> b727b8();
            case 0x727c8 -> b727c8();
            case 0x727d8 -> b727d8();
            case 0x72930 -> b72930();
            case 0x7295c -> b7295c();
            case 0x72988 -> b72988();
            case 0x72990 -> b72990();
            case 0x72998 -> b72998();
            case 0x729a0 -> b729a0();
            case 0x729a8 -> b729a8();
            case 0x729b0 -> b729b0();
            case 0x729b8 -> b729b8();
            case 0x729c0 -> b729c0();
            case 0x729c8 -> b729c8();
            case 0x729dc -> b729dc();
            case 0x72a1c -> b72a1c();
            case 0x72a24 -> b72a24();
            case 0x72a2c -> b72a2c();
            case 0x72a34 -> b72a34();
            case 0x72a40 -> b72a40();
            case 0x72a60 -> b72a60();
            case 0x72b74 -> b72b74();
            case 0x72c60 -> b72c60();
            case 0x73000 -> b73000();
            case 0x73158 -> b73158();
            case 0x731f4 -> b731f4();
            case 0x769dc -> b769dc();
            default -> throw new IllegalStateException("procDsp2: pc 0x" + Integer.toHexString(pc));
            };
        }
    }

    private int b72060() {
        sp = sp - 160; // 72060: sub sp, sp, #0xa0
        { long pa = (sp + 80); st64(pa, v8l); st64(pa + 8, v9l); } // 72064: stp d8, d9, [sp, #0x50]
        st64((sp + 96), v10l); // 72068: str d10, [sp, #0x60]
        { long pa = sp; st64(pa, x19); st64(pa + 8, x20); } // 7206c: stp x19, x20, [sp]
        { long pa = (sp + 16); st64(pa, x21); st64(pa + 8, x22); } // 72070: stp x21, x22, [sp, #0x10]
        { long pa = (sp + 32); st64(pa, x23); st64(pa + 8, x24); } // 72074: stp x23, x24, [sp, #0x20]
        { long pa = (sp + 48); st64(pa, x25); st64(pa + 8, x26); } // 72078: stp x25, x26, [sp, #0x30]
        { long pa = (sp + 64); st64(pa, x27); st64(pa + 8, x28); } // 7207c: stp x27, x28, [sp, #0x40]
        x1 = (ld32((x0 + 20))) & 0xffffffffL; // 72080: ldr w1, [x0, #0x14]
        if ((int) x1 == 0) { return 0x72090; } return 0x72088;
    }

    private int b72088() {
        x2 = (ld32((x0 + 12))) & 0xffffffffL; // 72088: ldr w2, [x0, #0xc]
        if ((int) x2 != 0) { return 0x720b4; } return 0x72090;
    }

    private int b72090() {
        { long pa = sp; x19 = ld64(pa); x20 = ld64(pa + 8); } // 72090: ldp x19, x20, [sp]
        { long pa = (sp + 80); v8l = ld64(pa); v8h = 0; v9l = ld64(pa + 8); v9h = 0; } // 72094: ldp d8, d9, [sp, #0x50]
        { long pa = (sp + 16); x21 = ld64(pa); x22 = ld64(pa + 8); } // 72098: ldp x21, x22, [sp, #0x10]
        { long pa = (sp + 32); x23 = ld64(pa); x24 = ld64(pa + 8); } // 7209c: ldp x23, x24, [sp, #0x20]
        { long pa = (sp + 48); x25 = ld64(pa); x26 = ld64(pa + 8); } // 720a0: ldp x25, x26, [sp, #0x30]
        { long pa = (sp + 64); x27 = ld64(pa); x28 = ld64(pa + 8); } // 720a4: ldp x27, x28, [sp, #0x40]
        v10l = ld64((sp + 96)); v10h = 0; // 720a8: ldr d10, [sp, #0x60]
        sp = sp + 160; // 720ac: add sp, sp, #0xa0
        return -1;
    }

    private int b720b4() {
        x1 = x0 + 36864; // 720b4: add x1, x0, #0x9, lsl #12
        x3 = (ld32((x1 + 2672))) & 0xffffffffL; // 720b8: ldr w3, [x1, #0xa70]
        st32((sp + 112), (int) x3); // 720bc: str w3, [sp, #0x70]
        x4 = ((int) x3 + (int) (1)) & 0xffffffffL; // 720c0: add w4, w3, #0x1
        { int fa = (int) x4, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 720c4: cmp w4, #0x20
        if (z) { return 0x727c8; } return 0x720cc;
    }

    private int b720cc() {
        st32((x1 + 2672), (int) x4); // 720cc: str w4, [x1, #0xa70]
        return 0x720d0;
    }

    private int b720d0() {
        { int fa = (int) x4, fb = (int) (1); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } x5 = ((int) x4 - (int) (1)) & 0xffffffffL; // 720d0: subs w5, w4, #0x1
        x6 = ((int) x4 + (int) (31)) & 0xffffffffL; // 720d4: add w6, w4, #0x1f
        x7 = ((n) ? (int) x6 : (int) x5) & 0xffffffffL; // 720d8: csel w7, w6, w5, mi
        x10 = (ld32((x1 + 2672))) & 0xffffffffL; // 720dc: ldr w10, [x1, #0xa70]
        { int fa = (int) x7, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 720e0: cmp w7, #0x20
        x8 = ((int) x7 & (int) (31)) & 0xffffffffL; // 720e4: and w8, w7, #0x1f
        x9 = ((c) ? (int) x8 : (int) x7) & 0xffffffffL; // 720e8: csel w9, w8, w7, hs
        x12 = ((int) x10 + (int) (30)) & 0xffffffffL; // 720ec: add w12, w10, #0x1e
        { int fa = (int) x10, fb = (int) (2); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } x11 = ((int) x10 - (int) (2)) & 0xffffffffL; // 720f0: subs w11, w10, #0x2
        st32((x1 + 2676), (int) x9); // 720f4: str w9, [x1, #0xa74]
        x13 = ((n) ? (int) x12 : (int) x11) & 0xffffffffL; // 720f8: csel w13, w12, w11, mi
        x25 = (3) & 0xffffffffL; // 720fc: mov w25, #0x3
        x14 = ((int) x13 & (int) (31)) & 0xffffffffL; // 72100: and w14, w13, #0x1f
        { int fa = (int) x13, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72104: cmp w13, #0x20
        x15 = ((c) ? (int) x14 : (int) x13) & 0xffffffffL; // 72108: csel w15, w14, w13, hs
        x27 = x1 + 2684; // 7210c: add x27, x1, #0xa7c
        st32((x1 + 2680), (int) x15); // 72110: str w15, [x1, #0xa78]
        x15 = (ld32((x1 + 2672))) & 0xffffffffL; // 72114: ldr w15, [x1, #0xa70]
        return 0x721b4;
    }

    private int b7211c() {
        x20 = (ld32((x1 + 2672))) & 0xffffffffL; // 7211c: ldr w20, [x1, #0xa70]
        x27 = x27 + 20; // 72120: add x27, x27, #0x14
        x18 = ((int) x20 - (int) ((int) x22)) & 0xffffffffL; // 72124: sub w18, w20, w22
        { int fa = (int) x18, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72128: cmp w18, wzr
        x12 = ((int) x18 + (int) (32)) & 0xffffffffL; // 7212c: add w12, w18, #0x20
        x9 = ((n != v) ? (int) x12 : (int) x18) & 0xffffffffL; // 72130: csel w9, w12, w18, lt
        { int fa = (int) x9, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72134: cmp w9, #0x20
        x3 = ((int) x9 & (int) (31)) & 0xffffffffL; // 72138: and w3, w9, #0x1f
        x22 = ((c) ? (int) x3 : (int) x9) & 0xffffffffL; // 7213c: csel w22, w3, w9, hs
        st32((x27 + -16), (int) x22); // 72140: stur w22, [x27, #-0x10]
        x21 = (ld32((x1 + 2672))) & 0xffffffffL; // 72144: ldr w21, [x1, #0xa70]
        x23 = ((int) x21 - (int) ((int) x23)) & 0xffffffffL; // 72148: sub w23, w21, w23
        { int fa = (int) x23, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 7214c: cmp w23, wzr
        x4 = ((int) x23 + (int) (32)) & 0xffffffffL; // 72150: add w4, w23, #0x20
        x7 = ((n != v) ? (int) x4 : (int) x23) & 0xffffffffL; // 72154: csel w7, w4, w23, lt
        { int fa = (int) x7, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72158: cmp w7, #0x20
        x28 = ((int) x7 & (int) (31)) & 0xffffffffL; // 7215c: and w28, w7, #0x1f
        x2 = ((c) ? (int) x28 : (int) x7) & 0xffffffffL; // 72160: csel w2, w28, w7, hs
        st32((x19 + 4), (int) x2); // 72164: str w2, [x19, #0x4]
        x10 = (ld32((x1 + 2672))) & 0xffffffffL; // 72168: ldr w10, [x1, #0xa70]
        x16 = ((int) x10 - (int) ((int) x16)) & 0xffffffffL; // 7216c: sub w16, w10, w16
        { int fa = (int) x16, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72170: cmp w16, wzr
        x5 = ((int) x16 + (int) (32)) & 0xffffffffL; // 72174: add w5, w16, #0x20
        x11 = ((n != v) ? (int) x5 : (int) x16) & 0xffffffffL; // 72178: csel w11, w5, w16, lt
        { int fa = (int) x11, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 7217c: cmp w11, #0x20
        x6 = ((int) x11 & (int) (31)) & 0xffffffffL; // 72180: and w6, w11, #0x1f
        x19 = ((c) ? (int) x6 : (int) x11) & 0xffffffffL; // 72184: csel w19, w6, w11, hs
        st32((x27 + -8), (int) x19); // 72188: stur w19, [x27, #-0x8]
        x26 = (ld32((x1 + 2672))) & 0xffffffffL; // 7218c: ldr w26, [x1, #0xa70]
        x17 = ((int) x26 - (int) ((int) x17)) & 0xffffffffL; // 72190: sub w17, w26, w17
        { int fa = (int) x17, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72194: cmp w17, wzr
        x13 = ((int) x17 + (int) (32)) & 0xffffffffL; // 72198: add w13, w17, #0x20
        x24 = ((n != v) ? (int) x13 : (int) x17) & 0xffffffffL; // 7219c: csel w24, w13, w17, lt
        x8 = ((int) x24 & (int) (31)) & 0xffffffffL; // 721a0: and w8, w24, #0x1f
        { int fa = (int) x24, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 721a4: cmp w24, #0x20
        x14 = ((c) ? (int) x8 : (int) x24) & 0xffffffffL; // 721a8: csel w14, w8, w24, hs
        st32((x27 + -4), (int) x14); // 721ac: stur w14, [x27, #-0x4]
        x15 = (ld32((x1 + 2672))) & 0xffffffffL; // 721b0: ldr w15, [x1, #0xa70]
        return 0x721b4;
    }

    private int b721b4() {
        x18 = ((int) x15 - (int) ((int) x25)) & 0xffffffffL; // 721b4: sub w18, w15, w25
        x19 = x27; // 721b8: mov x19, x27
        { int fa = (int) x18, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 721bc: cmp w18, wzr
        x20 = ((int) x18 + (int) (32)) & 0xffffffffL; // 721c0: add w20, w18, #0x20
        x21 = ((n != v) ? (int) x20 : (int) x18) & 0xffffffffL; // 721c4: csel w21, w20, w18, lt
        x22 = ((int) x25 + (int) (1)) & 0xffffffffL; // 721c8: add w22, w25, #0x1
        { int fa = (int) x21, fb = (int) (32); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 721cc: cmp w21, #0x20
        x24 = ((int) x21 & (int) (31)) & 0xffffffffL; // 721d0: and w24, w21, #0x1f
        x26 = ((c) ? (int) x24 : (int) x21) & 0xffffffffL; // 721d4: csel w26, w24, w21, hs
        { int fa = (int) x22, fb = (int) (24); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 721d8: cmp w22, #0x18
        st32(x19, (int) x26); x19 = x19 + 4; // 721dc: str w26, [x19], #0x4
        x16 = ((int) x25 + (int) (3)) & 0xffffffffL; // 721e0: add w16, w25, #0x3
        x17 = ((int) x25 + (int) (4)) & 0xffffffffL; // 721e4: add w17, w25, #0x4
        x23 = ((int) x22 + (int) (1)) & 0xffffffffL; // 721e8: add w23, w22, #0x1
        x25 = ((int) x25 + (int) (5)) & 0xffffffffL; // 721ec: add w25, w25, #0x5
        if (!z) { return 0x7211c; } return 0x721f4;
    }

    private int b721f4() {
        x19 = x0 + 28672; // 721f4: add x19, x0, #0x7, lsl #12
        x27 = (ld32((x1 + 2604))) & 0xffffffffL; // 721f8: ldr w27, [x1, #0xa2c]
        x28 = ((int) x27 + (int) (1)) & 0xffffffffL; // 721fc: add w28, w27, #0x1
        x2 = (ld32((x19 + 1068))) & 0xffffffffL; // 72200: ldr w2, [x19, #0x42c]
        x3 = ((int) x2 + (int) (1)) & 0xffffffffL; // 72204: add w3, w2, #0x1
        { int fa = (int) x3, fb = (int) (32768); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72208: cmp w3, #0x8, lsl #12
        x4 = ((!z) ? (int) x3 : 0) & 0xffffffffL; // 7220c: csel w4, w3, wzr, ne
        st32((x19 + 1068), (int) x4); // 72210: str w4, [x19, #0x42c]
        st32((x1 + 2604), (int) x28); // 72214: str w28, [x1, #0xa2c]
        { int fa = (int) x28, fb = (int) (-(1)); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Long.compareUnsigned((fa & 0xffffffffL) + ((-fb) & 0xffffffffL), 0xffffffffL) > 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72218: cmn w28, #0x1
        if (z) { throw new IllegalStateException("procDsp2: 0x727d4"); } return 0x72220;
    }

    private int b72220() {
        x5 = (43691) & 0xffffffffL; // 72220: mov w5, #0xaaab
        x5 = ((x5 & ~(0xffffL << 16)) | (43690L << 16)) & 0xffffffffL; // 72224: movk w5, #0xaaaa, lsl #16
        x6 = (x28 & 0xffffffffL) * (x5 & 0xffffffffL); // 72228: umull x6, w28, w5
        x7 = x6 >>> 32; // 7222c: lsr x7, x6, #32
        x8 = ((int) x7 >>> 1) & 0xffffffffL; // 72230: lsr w8, w7, #1
        x9 = ((int) x8 + (int) (((int) x8 << 1))) & 0xffffffffL; // 72234: add w9, w8, w8, lsl #1
        { int fa = (int) x28, fb = (int) ((int) x9); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72238: cmp w28, w9
        if (z) { return 0x727d8; } return 0x72240;
    }

    private int b72240() {
        x13 = ld64(x0); // 72240: ldr x13, [x0]
        x8 = x13 + 3928064; // 72244: add x8, x13, #0x3bf, lsl #12
        x15 = ld64((x8 + 1952)); // 72248: ldr x15, [x8, #0x7a0]
        x16 = (ld32((x8 + 1960))) & 0xffffffffL; // 7224c: ldr w16, [x8, #0x7a8]
        if (x15 == 0) { throw new IllegalStateException("procDsp2: 0x73c5c"); } return 0x72254;
    }

    private int b72254() {
        x17 = ld64((x15 + 88)); // 72254: ldr x17, [x15, #0x58]
        if (x17 == 0) { throw new IllegalStateException("procDsp2: 0x73cd0"); } return 0x7225c;
    }

    private int b7225c() {
        x22 = (ld32((x17 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 7225c: ldr w22, [x17, w16, uxtw #2]
        x18 = ((int) x22 << 8) & 0xffffffffL; // 72260: lsl w18, w22, #8
        x24 = ld64((x15 + 96)); // 72264: ldr x24, [x15, #0x60]
        st32((x1 + 2612), (int) x18); // 72268: str w18, [x1, #0xa34]
        if (x24 == 0) { return 0x72278; } return 0x72270;
    }

    private int b72270() {
        x23 = (ld32((x24 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 72270: ldr w23, [x24, w16, uxtw #2]
        x24 = ((int) x23 << 8) & 0xffffffffL; // 72274: lsl w24, w23, #8
        return 0x72278;
    }

    private int b72278() {
        x25 = ld64(x15); // 72278: ldr x25, [x15]
        st32((x1 + 2616), (int) x24); // 7227c: str w24, [x1, #0xa38]
        if (x25 == 0) { throw new IllegalStateException("procDsp2: 0x73cc4"); } return 0x72284;
    }

    private int b72284() {
        x27 = (ld32((x25 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 72284: ldr w27, [x25, w16, uxtw #2]
        x26 = ((int) x27 << 8) & 0xffffffffL; // 72288: lsl w26, w27, #8
        x28 = (long) (int) x26; // 7228c: sxtw x28, w26
        v31l = x28; v31h = 0; // 72290: fmov d31, x28
        x2 = ld64((x15 + 8)); // 72294: ldr x2, [x15, #0x8]
        st32((x1 + 2620), (int) x26); // 72298: str w26, [x1, #0xa3c]
        if (x2 == 0) { throw new IllegalStateException("procDsp2: 0x73cb8"); } return 0x722a0;
    }

    private int b722a0() {
        x4 = (ld32((x2 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 722a0: ldr w4, [x2, w16, uxtw #2]
        x3 = ((int) x4 << 8) & 0xffffffffL; // 722a4: lsl w3, w4, #8
        x5 = (long) (int) x3; // 722a8: sxtw x5, w3
        v8l = x5; v8h = 0; // 722ac: fmov d8, x5
        x7 = ld64((x15 + 16)); // 722b0: ldr x7, [x15, #0x10]
        st32((x1 + 2624), (int) x3); // 722b4: str w3, [x1, #0xa40]
        if (x7 == 0) { throw new IllegalStateException("procDsp2: 0x73cb0"); } return 0x722bc;
    }

    private int b722bc() {
        x9 = (ld32((x7 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 722bc: ldr w9, [x7, w16, uxtw #2]
        v0l = x9 & 0xffffffffL; v0h = 0; // 722c0: fmov s0, w9
        v26l = (((v0l & 0xffffffffL) << 8) & 0xffffffffL) | ((v0l >>> 32) << 8 << 32); v26h = 0; // 722c4: shl v26.2s, v0.2s, #0x8
        x10 = ld64((x15 + 24)); // 722c8: ldr x10, [x15, #0x18]
        st32((x1 + 2628), (int) v26l); // 722cc: str s26, [x1, #0xa44]
        if (x10 == 0) { throw new IllegalStateException("procDsp2: 0x73fe4"); } return 0x722d4;
    }

    private int b722d4() {
        x11 = (ld32((x10 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 722d4: ldr w11, [x10, w16, uxtw #2]
        x21 = ((int) x11 << 8) & 0xffffffffL; // 722d8: lsl w21, w11, #8
        x20 = ld64((x15 + 32)); // 722dc: ldr x20, [x15, #0x20]
        st32((x1 + 2632), (int) x21); // 722e0: str w21, [x1, #0xa48]
        if (x20 == 0) { return 0x722f0; } return 0x722e8;
    }

    private int b722e8() {
        x12 = (ld32((x20 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 722e8: ldr w12, [x20, w16, uxtw #2]
        x20 = ((int) x12 << 8) & 0xffffffffL; // 722ec: lsl w20, w12, #8
        return 0x722f0;
    }

    private int b722f0() {
        x13 = ld64((x15 + 40)); // 722f0: ldr x13, [x15, #0x28]
        st32((x1 + 2636), (int) x20); // 722f4: str w20, [x1, #0xa4c]
        if (x13 == 0) { throw new IllegalStateException("procDsp2: 0x73fd8"); } return 0x722fc;
    }

    private int b722fc() {
        x6 = (ld32((x13 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 722fc: ldr w6, [x13, w16, uxtw #2]
        x14 = ((int) x6 << 8) & 0xffffffffL; // 72300: lsl w14, w6, #8
        x17 = (long) (int) x14; // 72304: sxtw x17, w14
        v9l = x17; v9h = 0; // 72308: fmov d9, x17
        x18 = ld64((x15 + 48)); // 7230c: ldr x18, [x15, #0x30]
        st32((x1 + 2640), (int) x14); // 72310: str w14, [x1, #0xa50]
        if (x18 == 0) { throw new IllegalStateException("procDsp2: 0x73fcc"); } return 0x72318;
    }

    private int b72318() {
        x23 = (ld32((x18 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 72318: ldr w23, [x18, w16, uxtw #2]
        x22 = ((int) x23 << 8) & 0xffffffffL; // 7231c: lsl w22, w23, #8
        x24 = (long) (int) x22; // 72320: sxtw x24, w22
        v10l = x24; v10h = 0; // 72324: fmov d10, x24
        x14 = ld64((x15 + 56)); // 72328: ldr x14, [x15, #0x38]
        st32((x1 + 2644), (int) x22); // 7232c: str w22, [x1, #0xa54]
        if (x14 == 0) { throw new IllegalStateException("procDsp2: 0x73fc4"); } return 0x72334;
    }

    private int b72334() {
        x26 = (ld32((x14 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 72334: ldr w26, [x14, w16, uxtw #2]
        x25 = ((int) x26 << 8) & 0xffffffffL; // 72338: lsl w25, w26, #8
        x14 = (long) (int) x25; // 7233c: sxtw x14, w25
        x6 = ld64((x15 + 64)); // 72340: ldr x6, [x15, #0x40]
        st32((x1 + 2648), (int) x25); // 72344: str w25, [x1, #0xa58]
        if (x6 == 0) { throw new IllegalStateException("procDsp2: 0x73fbc"); } return 0x7234c;
    }

    private int b7234c() {
        x15 = (ld32((x6 + ((x16 & 0xffffffffL) << 2)))) & 0xffffffffL; // 7234c: ldr w15, [x6, w16, uxtw #2]
        x16 = ((int) x15 << 8) & 0xffffffffL; // 72350: lsl w16, w15, #8
        st32((x1 + 2652), (int) x16); // 72354: str w16, [x1, #0xa5c]
        x6 = (long) (int) x16; // 72358: sxtw x6, w16
        x7 = (ld32((x1 + 2672))) & 0xffffffffL; // 7235c: ldr w7, [x1, #0xa70]
        x17 = ((int) x7) & 0xffffffffL; // 72360: mov w17, w7
        x5 = x0 + (x17 << 2); // 72364: add x5, x0, x17, lsl #2
        x2 = x5 + 40960; // 72368: add x2, x5, #0xa, lsl #12
        st32((x2 + 1872), 0); // 7236c: str wzr, [x2, #0x750]
        st32((x2 + 2000), 0); // 72370: str wzr, [x2, #0x7d0]
        st32((x2 + 720), 0); // 72374: str wzr, [x2, #0x2d0]
        st32((x2 + 848), 0); // 72378: str wzr, [x2, #0x350]
        st32((x2 + 976), 0); // 7237c: str wzr, [x2, #0x3d0]
        st32((x2 + 1104), 0); // 72380: str wzr, [x2, #0x450]
        st32((x2 + 2128), 0); // 72384: str wzr, [x2, #0x850]
        st32((x2 + 2256), 0); // 72388: str wzr, [x2, #0x8d0]
        x18 = (ld32((x0 + 7740))) & 0xffffffffL; // 7238c: ldr w18, [x0, #0x1e3c]
        if ((int) x18 == 0) { return 0x7295c; } throw new IllegalStateException("procDsp2: 0x72394");
    }

    private int b72398() {
        x12 = v31l; // 72398: fmov x12, d31
        x21 = (long) ld32((x0 + 13960)); // 7239c: ldrsw x21, [x0, #0x3688]
        x25 = v9l; // 723a0: fmov x25, d9
        x16 = (long) ld32((x0 + 13964)); // 723a4: ldrsw x16, [x0, #0x368c]
        x4 = v8l; // 723a8: fmov x4, d8
        x15 = (long) ld32((x0 + 13968)); // 723ac: ldrsw x15, [x0, #0x3690]
        x27 = v10l; // 723b0: fmov x27, d10
        x23 = (long) ld32((x0 + 13980)); // 723b4: ldrsw x23, [x0, #0x369c]
        x9 = (long) ld32((x0 + 13972)); // 723b8: ldrsw x9, [x0, #0x3694]
        x7 = (long) ld32((x0 + 13976)); // 723bc: ldrsw x7, [x0, #0x3698]
        x10 = x21 * x12; // 723c0: mul x10, x21, x12
        x28 = x15 * x25; // 723c4: mul x28, x15, x25
        x11 = x16 * x4; // 723c8: mul x11, x16, x4
        x14 = x7 * x14; // 723cc: mul x14, x7, x14
        x6 = x23 * x6; // 723d0: mul x6, x23, x6
        x24 = x9 * x27; // 723d4: mul x24, x9, x27
        x25 = x28 >> 15; // 723d8: asr x25, x28, #15
        x23 = x14 >> 15; // 723dc: asr x23, x14, #15
        x16 = x10 >> 15; // 723e0: asr x16, x10, #15
        x26 = x11 >> 15; // 723e4: asr x26, x11, #15
        x28 = x24 >> 15; // 723e8: asr x28, x24, #15
        x12 = x6 >> 15; // 723ec: asr x12, x6, #15
        x13 = ((int) x16) & 0xffffffffL; // 723f0: mov w13, w16
        x14 = ((int) x26) & 0xffffffffL; // 723f4: mov w14, w26
        x15 = ((int) x25) & 0xffffffffL; // 723f8: mov w15, w25
        x20 = ((int) x28) & 0xffffffffL; // 723fc: mov w20, w28
        return 0x72400;
    }

    private int b72400() {
        x21 = ((int) x23) & 0xffffffffL; // 72400: mov w21, w23
        x22 = ((int) x12) & 0xffffffffL; // 72404: mov w22, w12
        if ((int) x18 != 0) { return 0x724c0; } return 0x7240c;
    }

    private int b7240c() {
        x22 = (ld32((x0 + 13984))) & 0xffffffffL; // 7240c: ldr w22, [x0, #0x36a0]
        x20 = (ld32((x3 + 3152))) & 0xffffffffL; // 72410: ldr w20, [x3, #0xc50]
        x21 = (ld32((x0 + 13988))) & 0xffffffffL; // 72414: ldr w21, [x0, #0x36a4]
        x13 = (ld32((x3 + 3408))) & 0xffffffffL; // 72418: ldr w13, [x3, #0xd50]
        x15 = (ld32((x0 + 13992))) & 0xffffffffL; // 7241c: ldr w15, [x0, #0x36a8]
        x11 = (ld32((x3 + 3280))) & 0xffffffffL; // 72420: ldr w11, [x3, #0xcd0]
        x4 = (ld32((x0 + 13996))) & 0xffffffffL; // 72424: ldr w4, [x0, #0x36ac]
        x10 = (ld32((x3 + 3536))) & 0xffffffffL; // 72428: ldr w10, [x3, #0xdd0]
        x9 = (long) (int) x22 * (long) (int) x20; // 7242c: smull x9, w22, w20
        x7 = (long) (int) x21 * (long) (int) x13; // 72430: smull x7, w21, w13
        x27 = (long) (int) x15 * (long) (int) x11; // 72434: smull x27, w15, w11
        x24 = (long) (int) x4 * (long) (int) x10; // 72438: smull x24, w4, w10
        x22 = x7 >> 15; // 7243c: asr x22, x7, #15
        x21 = x27 >> 15; // 72440: asr x21, x27, #15
        x6 = x24 >> 15; // 72444: asr x6, x24, #15
        x14 = x9 >> 15; // 72448: asr x14, x9, #15
        x7 = ((int) x21 + (int) ((int) x6)) & 0xffffffffL; // 7244c: add w7, w21, w6
        x9 = ((int) x14 + (int) ((int) x22)) & 0xffffffffL; // 72450: add w9, w14, w22
        x20 = (ld32((x0 + 14000))) & 0xffffffffL; // 72454: ldr w20, [x0, #0x36b0]
        x4 = (x9 << 32) >> 33; // 72458: sbfx x4, x9, #1, #31
        x13 = (ld32((x0 + 14004))) & 0xffffffffL; // 7245c: ldr w13, [x0, #0x36b4]
        x24 = (x7 << 32) >> 33; // 72460: sbfx x24, x7, #1, #31
        x11 = (ld32((x0 + 14008))) & 0xffffffffL; // 72464: ldr w11, [x0, #0x36b8]
        x22 = ((int) x4 + (int) ((int) x24)) & 0xffffffffL; // 72468: add w22, w4, w24
        x10 = (ld32((x0 + 14012))) & 0xffffffffL; // 7246c: ldr w10, [x0, #0x36bc]
        x27 = (ld32((x0 + 14016))) & 0xffffffffL; // 72470: ldr w27, [x0, #0x36c0]
        x15 = (ld32((x0 + 14020))) & 0xffffffffL; // 72474: ldr w15, [x0, #0x36c4]
        x14 = (long) (int) x9 * (long) (int) x20; // 72478: smull x14, w9, w20
        x21 = (long) (int) x11 * (long) (int) x22; // 7247c: smull x21, w11, w22
        x20 = (long) (int) x7 * (long) (int) x13; // 72480: smull x20, w7, w13
        x11 = (long) (int) x22 * (long) (int) x10; // 72484: smull x11, w22, w10
        x6 = (long) (int) x22 * (long) (int) x27; // 72488: smull x6, w22, w27
        x9 = (long) (int) x22 * (long) (int) x15; // 7248c: smull x9, w22, w15
        x13 = x14 >> 15; // 72490: asr x13, x14, #15
        x10 = x20 >> 15; // 72494: asr x10, x20, #15
        x27 = x21 >> 15; // 72498: asr x27, x21, #15
        x4 = x11 >> 15; // 7249c: asr x4, x11, #15
        x7 = x6 >> 15; // 724a0: asr x7, x6, #15
        x13 = ((int) x16 + (int) ((int) x13)) & 0xffffffffL; // 724a4: add w13, w16, w13
        x16 = x9 >> 15; // 724a8: asr x16, x9, #15
        x14 = ((int) x26 + (int) ((int) x10)) & 0xffffffffL; // 724ac: add w14, w26, w10
        x15 = ((int) x25 + (int) ((int) x27)) & 0xffffffffL; // 724b0: add w15, w25, w27
        x20 = ((int) x28 + (int) ((int) x4)) & 0xffffffffL; // 724b4: add w20, w28, w4
        x21 = ((int) x23 + (int) ((int) x7)) & 0xffffffffL; // 724b8: add w21, w23, w7
        x22 = ((int) x12 + (int) ((int) x16)) & 0xffffffffL; // 724bc: add w22, w12, w16
        return 0x724c0;
    }

    private int b724c0() {
        st32((x2 + 464), (int) x13); // 724c0: str w13, [x2, #0x1d0]
        st32((x2 + 592), (int) x14); // 724c4: str w14, [x2, #0x250]
        st32((x3 + 4048), (int) x15); // 724c8: str w15, [x3, #0xfd0]
        st32((x2 + 80), (int) x20); // 724cc: str w20, [x2, #0x50]
        st32((x2 + 208), (int) x21); // 724d0: str w21, [x2, #0xd0]
        st32((x2 + 336), (int) x22); // 724d4: str w22, [x2, #0x150]
        x23 = (ld32((x0 + 7760))) & 0xffffffffL; // 724d8: ldr w23, [x0, #0x1e50]
        if ((int) x23 == 0) { return 0x72a60; } throw new IllegalStateException("procDsp2: 0x724e0");
    }

    private int b72500() {
        x24 = (ld32((x8 + 2016))) & 0xffffffffL; // 72500: ldr w24, [x8, #0x7e0]
        if ((int) x24 != 0) { throw new IllegalStateException("procDsp2: 0x727f0"); } return 0x72508;
    }

    private int b72508() {
        x8 = (ld32((x1 + 2676))) & 0xffffffffL; // 72508: ldr w8, [x1, #0xa74]
        x27 = x0 + 16384; // 7250c: add x27, x0, #0x4, lsl #12
        x28 = x5 + 1093632; // 72510: add x28, x5, #0x10b, lsl #12
        x23 = (ld32((x0 + 15704))) & 0xffffffffL; // 72514: ldr w23, [x0, #0x3d58]
        x7 = (ld32((x0 + 15708))) & 0xffffffffL; // 72518: ldr w7, [x0, #0x3d5c]
        { int fa = (int) x18, fb = (int) (1); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 7251c: cmp w18, #0x1
        x10 = x0 + (x8 << 2); // 72520: add x10, x0, x8, lsl #2
        x19 = (ld32((x27 + 352))) & 0xffffffffL; // 72524: ldr w19, [x27, #0x160]
        x6 = x10 + 1093632; // 72528: add x6, x10, #0x10b, lsl #12
        x16 = (ld32((x27 + 356))) & 0xffffffffL; // 7252c: ldr w16, [x27, #0x164]
        x13 = (long) (int) x23 * (long) (int) x15; // 72530: smull x13, w23, w15
        x9 = (long) (int) x15 * (long) (int) x19; // 72534: smull x9, w15, w19
        x25 = (ld32((x6 + 80))) & 0xffffffffL; // 72538: ldr w25, [x6, #0x50]
        x14 = x13 >> 15; // 7253c: asr x14, x13, #15
        x4 = x9 >> 15; // 72540: asr x4, x9, #15
        x17 = (long) (int) x15 * (long) (int) x7; // 72544: smull x17, w15, w7
        x26 = (long) (int) x16 * (long) (int) x25; // 72548: smull x26, w16, w25
        x12 = x17 >> 15; // 7254c: asr x12, x17, #15
        x11 = x26 >> 15; // 72550: asr x11, x26, #15
        x24 = ((int) x4 + (int) ((int) x11)) & 0xffffffffL; // 72554: add w24, w4, w11
        st32((x28 + 80), (int) x24); // 72558: str w24, [x28, #0x50]
        x23 = (ld32((x6 + 208))) & 0xffffffffL; // 7255c: ldr w23, [x6, #0xd0]
        x5 = (ld32((x27 + 360))) & 0xffffffffL; // 72560: ldr w5, [x27, #0x168]
        x8 = (ld32((x27 + 364))) & 0xffffffffL; // 72564: ldr w8, [x27, #0x16c]
        x10 = (ld32((x0 + 14028))) & 0xffffffffL; // 72568: ldr w10, [x0, #0x36cc]
        x7 = (long) (int) x20 * (long) (int) x5; // 7256c: smull x7, w20, w5
        x19 = (long) (int) x8 * (long) (int) x23; // 72570: smull x19, w8, w23
        x16 = x7 >> 15; // 72574: asr x16, x7, #15
        x13 = (ld32((x0 + 15712))) & 0xffffffffL; // 72578: ldr w13, [x0, #0x3d60]
        x25 = x19 >> 15; // 7257c: asr x25, x19, #15
        x9 = (ld32((x0 + 15716))) & 0xffffffffL; // 72580: ldr w9, [x0, #0x3d64]
        x11 = ((int) x16 + (int) ((int) x25)) & 0xffffffffL; // 72584: add w11, w16, w25
        st32((x28 + 208), (int) x11); // 72588: str w11, [x28, #0xd0]
        x15 = (long) (int) x15 * (long) (int) x10; // 7258c: smull x15, w15, w10
        x23 = (ld32((x27 + 368))) & 0xffffffffL; // 72590: ldr w23, [x27, #0x170]
        x24 = ((int) x24 + (int) ((int) x11)) & 0xffffffffL; // 72594: add w24, w24, w11
        x10 = (ld32((x6 + 336))) & 0xffffffffL; // 72598: ldr w10, [x6, #0x150]
        x26 = x15 >> 15; // 7259c: asr x26, x15, #15
        x7 = (ld32((x27 + 372))) & 0xffffffffL; // 725a0: ldr w7, [x27, #0x174]
        x17 = (long) (int) x13 * (long) (int) x20; // 725a4: smull x17, w13, w20
        x19 = (long) (int) x21 * (long) (int) x23; // 725a8: smull x19, w21, w23
        x13 = (long) (int) x7 * (long) (int) x10; // 725ac: smull x13, w7, w10
        x16 = (ld32((x0 + 14032))) & 0xffffffffL; // 725b0: ldr w16, [x0, #0x36d0]
        x5 = x17 >> 15; // 725b4: asr x5, x17, #15
        x25 = x19 >> 15; // 725b8: asr x25, x19, #15
        x17 = x13 >> 15; // 725bc: asr x17, x13, #15
        x15 = (ld32((x0 + 15720))) & 0xffffffffL; // 725c0: ldr w15, [x0, #0x3d68]
        x23 = ((int) x25 + (int) ((int) x17)) & 0xffffffffL; // 725c4: add w23, w25, w17
        x4 = (long) (int) x20 * (long) (int) x9; // 725c8: smull x4, w20, w9
        x9 = (ld32((x0 + 15724))) & 0xffffffffL; // 725cc: ldr w9, [x0, #0x3d6c]
        x14 = ((int) x14 + (int) ((int) x5)) & 0xffffffffL; // 725d0: add w14, w14, w5
        st32((x28 + 336), (int) x23); // 725d4: str w23, [x28, #0x150]
        x8 = x4 >> 15; // 725d8: asr x8, x4, #15
        x20 = (long) (int) x20 * (long) (int) x16; // 725dc: smull x20, w20, w16
        x10 = (ld32((x27 + 376))) & 0xffffffffL; // 725e0: ldr w10, [x27, #0x178]
        x12 = ((int) x12 + (int) ((int) x8)) & 0xffffffffL; // 725e4: add w12, w12, w8
        x16 = (ld32((x27 + 380))) & 0xffffffffL; // 725e8: ldr w16, [x27, #0x17c]
        x11 = x20 >> 15; // 725ec: asr x11, x20, #15
        x6 = (ld32((x6 + 464))) & 0xffffffffL; // 725f0: ldr w6, [x6, #0x1d0]
        x26 = ((int) x26 + (int) ((int) x11)) & 0xffffffffL; // 725f4: add w26, w26, w11
        x19 = (long) (int) x22 * (long) (int) x10; // 725f8: smull x19, w22, w10
        x4 = (long) (int) x15 * (long) (int) x21; // 725fc: smull x4, w15, w21
        x25 = (long) (int) x16 * (long) (int) x6; // 72600: smull x25, w16, w6
        x17 = (ld32((x0 + 15728))) & 0xffffffffL; // 72604: ldr w17, [x0, #0x3d70]
        x15 = x19 >> 15; // 72608: asr x15, x19, #15
        x20 = x25 >> 15; // 7260c: asr x20, x25, #15
        x13 = (ld32((x0 + 14036))) & 0xffffffffL; // 72610: ldr w13, [x0, #0x36d4]
        x11 = ((int) x15 + (int) ((int) x20)) & 0xffffffffL; // 72614: add w11, w15, w20
        x7 = x4 >> 15; // 72618: asr x7, x4, #15
        x5 = (long) (int) x21 * (long) (int) x9; // 7261c: smull x5, w21, w9
        x9 = (ld32((x0 + 15732))) & 0xffffffffL; // 72620: ldr w9, [x0, #0x3d74]
        x14 = ((int) x14 + (int) ((int) x7)) & 0xffffffffL; // 72624: add w14, w14, w7
        st32((x28 + 464), (int) x11); // 72628: str w11, [x28, #0x1d0]
        x8 = x5 >> 15; // 7262c: asr x8, x5, #15
        x4 = (long) (int) x17 * (long) (int) x22; // 72630: smull x4, w17, w22
        x10 = (ld32((x0 + 15696))) & 0xffffffffL; // 72634: ldr w10, [x0, #0x3d50]
        x12 = ((int) x12 + (int) ((int) x8)) & 0xffffffffL; // 72638: add w12, w12, w8
        x6 = (ld32((x1 + 2612))) & 0xffffffffL; // 7263c: ldr w6, [x1, #0xa34]
        x28 = x4 >> 15; // 72640: asr x28, x4, #15
        x19 = (ld32((x0 + 15700))) & 0xffffffffL; // 72644: ldr w19, [x0, #0x3d54]
        x7 = ((int) x14 + (int) ((int) x28)) & 0xffffffffL; // 72648: add w7, w14, w28
        x8 = (ld32((x1 + 2616))) & 0xffffffffL; // 7264c: ldr w8, [x1, #0xa38]
        x24 = ((int) x24 + (int) ((int) x23)) & 0xffffffffL; // 72650: add w24, w24, w23
        x5 = (long) (int) x22 * (long) (int) x9; // 72654: smull x5, w22, w9
        x21 = (long) (int) x21 * (long) (int) x13; // 72658: smull x21, w21, w13
        x14 = (ld32((x0 + 14040))) & 0xffffffffL; // 7265c: ldr w14, [x0, #0x36d8]
        x16 = x5 >> 15; // 72660: asr x16, x5, #15
        x13 = (long) (int) x10 * (long) (int) x6; // 72664: smull x13, w10, w6
        x9 = (ld32((x0 + 14052))) & 0xffffffffL; // 72668: ldr w9, [x0, #0x36e4]
        x25 = ((int) x12 + (int) ((int) x16)) & 0xffffffffL; // 7266c: add w25, w12, w16
        x15 = (long) (int) x19 * (long) (int) x8; // 72670: smull x15, w19, w8
        x20 = (ld32((x0 + 14056))) & 0xffffffffL; // 72674: ldr w20, [x0, #0x36e8]
        x17 = x13 >> 15; // 72678: asr x17, x13, #15
        x12 = x15 >> 15; // 7267c: asr x12, x15, #15
        x23 = x21 >> 15; // 72680: asr x23, x21, #15
        x22 = (long) (int) x22 * (long) (int) x14; // 72684: smull x22, w22, w14
        x4 = (long) (int) x9 * (long) (int) x17; // 72688: smull x4, w9, w17
        x26 = ((int) x26 + (int) ((int) x23)) & 0xffffffffL; // 7268c: add w26, w26, w23
        x5 = x22 >> 15; // 72690: asr x5, x22, #15
        x28 = (long) (int) x20 * (long) (int) x12; // 72694: smull x28, w20, w12
        x10 = x4 >> 15; // 72698: asr x10, x4, #15
        x26 = ((int) x26 + (int) ((int) x5)) & 0xffffffffL; // 7269c: add w26, w26, w5
        x24 = ((int) x24 + (int) ((int) x11)) & 0xffffffffL; // 726a0: add w24, w24, w11
        x21 = (ld32((x2 + 720))) & 0xffffffffL; // 726a4: ldr w21, [x2, #0x2d0]
        x23 = (ld32((x2 + 848))) & 0xffffffffL; // 726a8: ldr w23, [x2, #0x350]
        x19 = x28 >> 15; // 726ac: asr x19, x28, #15
        x16 = (ld32((x2 + 976))) & 0xffffffffL; // 726b0: ldr w16, [x2, #0x3d0]
        x13 = ((int) x26 + (int) ((int) x10)) & 0xffffffffL; // 726b4: add w13, w26, w10
        x11 = (ld32((x2 + 1104))) & 0xffffffffL; // 726b8: ldr w11, [x2, #0x450]
        x8 = ((int) x25 + (int) ((int) x12)) & 0xffffffffL; // 726bc: add w8, w25, w12
        x6 = ((int) x7 + (int) ((int) x17)) & 0xffffffffL; // 726c0: add w6, w7, w17
        x14 = ((int) x13 + (int) ((int) x19)) & 0xffffffffL; // 726c4: add w14, w13, w19
        x7 = ((int) x6 + (int) ((int) x21)) & 0xffffffffL; // 726c8: add w7, w6, w21
        x28 = ((int) x8 + (int) ((int) x23)) & 0xffffffffL; // 726cc: add w28, w8, w23
        x24 = ((int) x24 + (int) ((int) x11)) & 0xffffffffL; // 726d0: add w24, w24, w11
        x25 = ((int) x14 + (int) ((int) x16)) & 0xffffffffL; // 726d4: add w25, w14, w16
        st32((x2 + 720), (int) x7); // 726d8: str w7, [x2, #0x2d0]
        st32((x2 + 848), (int) x28); // 726dc: str w28, [x2, #0x350]
        st32((x2 + 1104), (int) x24); // 726e0: str w24, [x2, #0x450]
        st32((x2 + 976), (int) x25); // 726e4: str w25, [x2, #0x3d0]
        if (z) { throw new IllegalStateException("procDsp2: 0x73cd8"); } return 0x726ec;
    }

    private int b726ec() {
        x15 = (ld32((x0 + 7744))) & 0xffffffffL; // 726ec: ldr w15, [x0, #0x1e40]
        { int fa = (int) x15, fb = (int) (1); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 726f0: cmp w15, #0x1
        if (z) { throw new IllegalStateException("procDsp2: 0x73d00"); } return 0x726f8;
    }

    private int b726f8() {
        { int fa = (int) x18, fb = (int) (1); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 726f8: cmp w18, #0x1
        if (z) { throw new IllegalStateException("procDsp2: 0x73c30"); } return 0x72700;
    }

    private int b72700() {
        st32((x2 + 1616), (int) x7); // 72700: str w7, [x2, #0x650]
        st32((x2 + 1744), (int) x28); // 72704: str w28, [x2, #0x6d0]
        if ((int) x15 != 0) { return 0x72a40; } throw new IllegalStateException("procDsp2: 0x7270c");
    }

    private int b72714() {
        x19 = (ld32((x1 + 2672))) & 0xffffffffL; // 72714: ldr w19, [x1, #0xa70]
        x26 = (ld32((x27 + 2140))) & 0xffffffffL; // 72718: ldr w26, [x27, #0x85c]
        x13 = (ld32((x27 + 2144))) & 0xffffffffL; // 7271c: ldr w13, [x27, #0x860]
        x24 = x0 + (x19 << 2); // 72720: add x24, x0, x19, lsl #2
        x8 = (ld32((x0 + 7760))) & 0xffffffffL; // 72724: ldr w8, [x0, #0x1e50]
        x14 = x24 + 40960; // 72728: add x14, x24, #0xa, lsl #12
        x25 = ((int) x8 - (int) (2)) & 0xffffffffL; // 7272c: sub w25, w8, #0x2
        { int fa = (int) x25, fb = (int) (2); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72730: cmp w25, #0x2
        x15 = (ld32((x14 + 1872))) & 0xffffffffL; // 72734: ldr w15, [x14, #0x750]
        x17 = (ld32((x14 + 2000))) & 0xffffffffL; // 72738: ldr w17, [x14, #0x7d0]
        x20 = (ld32((x14 + 2384))) & 0xffffffffL; // 7273c: ldr w20, [x14, #0x950]
        x18 = (long) (int) x26 * (long) (int) x15; // 72740: smull x18, w26, w15
        x12 = (long) (int) x13 * (long) (int) x17; // 72744: smull x12, w13, w17
        x9 = (ld32((x14 + 2512))) & 0xffffffffL; // 72748: ldr w9, [x14, #0x9d0]
        x3 = x18 >> 15; // 7274c: asr x3, x18, #15
        x22 = x12 >> 15; // 72750: asr x22, x12, #15
        x16 = ((int) x20 + (int) ((int) x3)) & 0xffffffffL; // 72754: add w16, w20, w3
        x5 = ((int) x9 + (int) ((int) x22)) & 0xffffffffL; // 72758: add w5, w9, w22
        if (!c || z) { return 0x72930; } return 0x72760;
    }

    private int b72760() {
        if ((int) x8 == 0) { return 0x72930; } return 0x72764;
    }

    private int b72764() {
        st32((sp + 144), (int) x16); // 72764: str w16, [sp, #0x90]
        x19 = sp + 144; // 72768: add x19, sp, #0x90
        st32((sp + 148), (int) x5); // 7276c: str w5, [sp, #0x94]
        x11 = x1 + 2656; // 72770: add x11, x1, #0xa60
        st32((sp + 152), (int) x16); // 72774: str w16, [sp, #0x98]
        x6 = ((int) x16 << 2) & 0xffffffffL; // 72778: lsl w6, w16, #2
        st32((sp + 156), (int) x5); // 7277c: str w5, [sp, #0x9c]
        { long qa = x19; v6l = ld64(qa); v6h = ld64(qa + 8); } // 72780: ldr q6, [x19]
        x0 = ld64(x0); // 72784: ldr x0, [x0]
        x26 = x0 + 3928064; // 72788: add x26, x0, #0x3bf, lsl #12
        v16l = ((((v6l & 0xffffffffL) << 2) & 0xffffffffL) | ((v6l >>> 32) << 2 << 32)); v16h = ((((v6h & 0xffffffffL) << 2) & 0xffffffffL) | ((v6h >>> 32) << 2 << 32)); // 7278c: shl v16.4s, v6.4s, #0x2
        x13 = ld64((x26 + 1952)); // 72790: ldr x13, [x26, #0x7a0]
        { long qa = x11; st64(qa, v16l); st64(qa + 8, v16h); } // 72794: str q16, [x11]
        x24 = (ld32((x26 + 1960))) & 0xffffffffL; // 72798: ldr w24, [x26, #0x7a8]
        if (x13 == 0) { return 0x72090; } return 0x727a0;
    }

    private int b727a0() {
        x8 = ld64(x13); // 727a0: ldr x8, [x13]
        if (x8 == 0) { return 0x727b0; } return 0x727a8;
    }

    private int b727a8() {
        x14 = ((int) x6 >> 8) & 0xffffffffL; // 727a8: asr w14, w6, #8
        st32((x8 + ((x24 & 0xffffffffL) << 2)), (int) x14); // 727ac: str w14, [x8, w24, uxtw #2]
        return 0x727b0;
    }

    private int b727b0() {
        x25 = ld64((x13 + 8)); // 727b0: ldr x25, [x13, #0x8]
        if (x25 == 0) { return 0x72090; } return 0x727b8;
    }

    private int b727b8() {
        x1 = (ld32((x1 + 2668))) & 0xffffffffL; // 727b8: ldr w1, [x1, #0xa6c]
        x15 = ((int) x1 >> 8) & 0xffffffffL; // 727bc: asr w15, w1, #8
        st32((x25 + ((x24 & 0xffffffffL) << 2)), (int) x15); // 727c0: str w15, [x25, w24, uxtw #2]
        return 0x72090;
    }

    private int b727c8() {
        st32((x1 + 2672), 0); // 727c8: str wzr, [x1, #0xa70]
        x4 = (0) & 0xffffffffL; // 727cc: mov w4, #0x0
        return 0x720d0;
    }

    private int b727d8() {
        x10 = (ld32((x1 + 2600))) & 0xffffffffL; // 727d8: ldr w10, [x1, #0xa28]
        x11 = ((int) x10 + (int) (1)) & 0xffffffffL; // 727dc: add w11, w10, #0x1
        { int fa = (int) x11, fb = (int) (32768); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 727e0: cmp w11, #0x8, lsl #12
        x12 = ((!z) ? (int) x11 : 0) & 0xffffffffL; // 727e4: csel w12, w11, wzr, ne
        st32((x1 + 2600), (int) x12); // 727e8: str w12, [x1, #0xa28]
        return 0x72240;
    }

    private int b72930() {
        x21 = (ld32((x27 + 2148))) & 0xffffffffL; // 72930: ldr w21, [x27, #0x864]
        x23 = (ld32((x14 + 2128))) & 0xffffffffL; // 72934: ldr w23, [x14, #0x850]
        x27 = (ld32((x27 + 2152))) & 0xffffffffL; // 72938: ldr w27, [x27, #0x868]
        x4 = (ld32((x14 + 2256))) & 0xffffffffL; // 7293c: ldr w4, [x14, #0x8d0]
        x7 = (long) (int) x21 * (long) (int) x23; // 72940: smull x7, w21, w23
        x28 = (long) (int) x27 * (long) (int) x4; // 72944: smull x28, w27, w4
        x2 = x7 >> 15; // 72948: asr x2, x7, #15
        x10 = x28 >> 15; // 7294c: asr x10, x28, #15
        x16 = ((int) x16 + (int) ((int) x2)) & 0xffffffffL; // 72950: add w16, w16, w2
        x5 = ((int) x5 + (int) ((int) x10)) & 0xffffffffL; // 72954: add w5, w5, w10
        return 0x72764;
    }

    private int b7295c() {
        x27 = (ld32((x0 + 13932))) & 0xffffffffL; // 7295c: ldr w27, [x0, #0x366c]
        x3 = x5 + 36864; // 72960: add x3, x5, #0x9, lsl #12
        x21 = (long) (int) x27 * (long) (int) x21; // 72964: smull x21, w27, w21
        x16 = x21 >> 15; // 72968: asr x16, x21, #15
        st32((x3 + 2768), (int) x16); // 7296c: str w16, [x3, #0xad0]
        x28 = (ld32((x0 + 13936))) & 0xffffffffL; // 72970: ldr w28, [x0, #0x3670]
        x20 = (long) (int) x28 * (long) (int) x20; // 72974: smull x20, w28, w20
        x10 = x20 >> 15; // 72978: asr x10, x20, #15
        st32((x3 + 2896), (int) x10); // 7297c: str w10, [x3, #0xb50]
        x4 = (ld32((x0 + 7752))) & 0xffffffffL; // 72980: ldr w4, [x0, #0x1e48]
        if ((int) x4 == 0) { throw new IllegalStateException("procDsp2: 0x7413c"); } return 0x72988;
    }

    private int b72988() {
        { int fa = (int) x4, fb = (int) (1); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72988: cmp w4, #0x1
        if (z) { throw new IllegalStateException("procDsp2: 0x77aec"); } return 0x72990;
    }

    private int b72990() {
        { int fa = (int) x4, fb = (int) (2); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72990: cmp w4, #0x2
        if (z) { throw new IllegalStateException("procDsp2: 0x73200"); } return 0x72998;
    }

    private int b72998() {
        { int fa = (int) x4, fb = (int) (3); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72998: cmp w4, #0x3
        if (z) { throw new IllegalStateException("procDsp2: 0x775b4"); } return 0x729a0;
    }

    private int b729a0() {
        { int fa = (int) x4, fb = (int) (4); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 729a0: cmp w4, #0x4
        if (z) { throw new IllegalStateException("procDsp2: 0x77108"); } return 0x729a8;
    }

    private int b729a8() {
        { int fa = (int) x4, fb = (int) (5); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 729a8: cmp w4, #0x5
        if (z) { throw new IllegalStateException("procDsp2: 0x76d70"); } return 0x729b0;
    }

    private int b729b0() {
        { int fa = (int) x4, fb = (int) (6); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 729b0: cmp w4, #0x6
        if (z) { throw new IllegalStateException("procDsp2: 0x769fc"); } return 0x729b8;
    }

    private int b729b8() {
        { int fa = (int) x4, fb = (int) (8); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 729b8: cmp w4, #0x8
        if (z) { throw new IllegalStateException("procDsp2: 0x769ec"); } return 0x729c0;
    }

    private int b729c0() {
        { int fa = (int) x4, fb = (int) (7); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 729c0: cmp w4, #0x7
        if (z) { throw new IllegalStateException("procDsp2: 0x783a0"); } return 0x729c8;
    }

    private int b729c8() {
        { int fa = (int) x4, fb = (int) (9); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 729c8: cmp w4, #0x9
        if (z) { return 0x769dc; } throw new IllegalStateException("procDsp2: 0x729d0");
    }

    private int b729dc() {
        x22 = ((int) v26l) & 0xffffffffL; // 729dc: fmov w22, s26
        x27 = (ld32((x0 + 13940))) & 0xffffffffL; // 729e0: ldr w27, [x0, #0x3674]
        x4 = (ld32((x0 + 13944))) & 0xffffffffL; // 729e4: ldr w4, [x0, #0x3678]
        x28 = (ld32((x3 + 3408))) & 0xffffffffL; // 729e8: ldr w28, [x3, #0xd50]
        x26 = (long) ld32((x0 + 13948)); // 729ec: ldrsw x26, [x0, #0x367c]
        x21 = (long) (int) x27 * (long) (int) x22; // 729f0: smull x21, w27, w22
        x15 = (long) (int) x4 * (long) (int) x28; // 729f4: smull x15, w4, w28
        x12 = x26 * x11; // 729f8: mul x12, x26, x11
        x20 = x21 >> 15; // 729fc: asr x20, x21, #15
        x13 = x15 >> 15; // 72a00: asr x13, x15, #15
        x10 = ((int) x20 + (int) ((int) x13)) & 0xffffffffL; // 72a04: add w10, w20, w13
        x16 = x12 >> 15; // 72a08: asr x16, x12, #15
        x23 = ((int) x10 + (int) ((int) x16)) & 0xffffffffL; // 72a0c: add w23, w10, w16
        st32((x3 + 3024), (int) x23); // 72a10: str w23, [x3, #0xbd0]
        x10 = (ld32((x0 + 7748))) & 0xffffffffL; // 72a14: ldr w10, [x0, #0x1e44]
        if ((int) x10 == 0) { throw new IllegalStateException("procDsp2: 0x74b50"); } return 0x72a1c;
    }

    private int b72a1c() {
        { int fa = (int) x10, fb = (int) (1); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72a1c: cmp w10, #0x1
        if (z) { throw new IllegalStateException("procDsp2: 0x7632c"); } return 0x72a24;
    }

    private int b72a24() {
        { int fa = (int) x10, fb = (int) (2); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72a24: cmp w10, #0x2
        if (z) { throw new IllegalStateException("procDsp2: 0x76320"); } return 0x72a2c;
    }

    private int b72a2c() {
        { int fa = (int) x10, fb = (int) (3); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72a2c: cmp w10, #0x3
        if (!z) { return 0x72398; } return 0x72a34;
    }

    private int b72a34() {
        st32((x3 + 3152), 0); // 72a34: str wzr, [x3, #0xc50]
        st32((x3 + 3280), 0); // 72a38: str wzr, [x3, #0xcd0]
        return 0x72398;
    }

    private int b72a40() {
        x2 = ld64((sp + 112)); // 72a40: ldr x2, [sp, #0x70]
        x10 = x0 + ((x2 & 0xffffffffL) << 2); // 72a44: add x10, x0, w2, uxtw #2
        x16 = x10 + 40960; // 72a48: add x16, x10, #0xa, lsl #12
        x11 = (ld32((x16 + 1616))) & 0xffffffffL; // 72a4c: ldr w11, [x16, #0x650]
        x6 = (ld32((x16 + 1744))) & 0xffffffffL; // 72a50: ldr w6, [x16, #0x6d0]
        st32((x16 + 2384), (int) x11); // 72a54: str w11, [x16, #0x950]
        st32((x16 + 2512), (int) x6); // 72a58: str w6, [x16, #0x9d0]
        return 0x72714;
    }

    private int b72a60() {
        x11 = (ld32((x19 + 1068))) & 0xffffffffL; // 72a60: ldr w11, [x19, #0x42c]
        x25 = (32767) & 0xffffffffL; // 72a64: mov w25, #0x7fff
        x4 = ((int) (short) ld16((x1 + 2466))) & 0xffffffffL; // 72a68: ldrsh w4, [x1, #0x9a2]
        x7 = ((int) x11 - (int) (1)) & 0xffffffffL; // 72a6c: sub w7, w11, #0x1
        x12 = ((int) x11 - (int) (2)) & 0xffffffffL; // 72a70: sub w12, w11, #0x2
        { int fa = (int) x7, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72a74: cmp w7, wzr
        x26 = ((int) x7 + (int) (32768)) & 0xffffffffL; // 72a78: add w26, w7, #0x8, lsl #12
        x19 = ((n != v) ? (int) x26 : (int) x7) & 0xffffffffL; // 72a7c: csel w19, w26, w7, lt
        x10 = ((int) x12 + (int) (32768)) & 0xffffffffL; // 72a80: add w10, w12, #0x8, lsl #12
        { int fa = (int) x19, fb = (int) ((int) x25); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72a84: cmp w19, w25
        x28 = ((int) x19 & (int) ((int) x25)) & 0xffffffffL; // 72a88: and w28, w19, w25
        x27 = ((c && !z) ? (int) x28 : (int) x19) & 0xffffffffL; // 72a8c: csel w27, w28, w19, hi
        { int fa = (int) x12, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72a90: cmp w12, wzr
        x24 = ((n != v) ? (int) x10 : (int) x12) & 0xffffffffL; // 72a94: csel w24, w10, w12, lt
        x6 = (ld32((x1 + 2676))) & 0xffffffffL; // 72a98: ldr w6, [x1, #0xa74]
        x9 = ((int) x24 & (int) ((int) x25)) & 0xffffffffL; // 72a9c: and w9, w24, w25
        { int fa = (int) x24, fb = (int) ((int) x25); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72aa0: cmp w24, w25
        x7 = (ld32((x1 + 2680))) & 0xffffffffL; // 72aa4: ldr w7, [x1, #0xa78]
        x26 = ((c && !z) ? (int) x9 : (int) x24) & 0xffffffffL; // 72aa8: csel w26, w9, w24, hi
        if ((x4 & (1L << 31)) == 0) { return 0x72b74; } throw new IllegalStateException("procDsp2: 0x72ab0");
    }

    private int b72b74() {
        x25 = ((int) x11 - (int) ((int) x4)) & 0xffffffffL; // 72b74: sub w25, w11, w4
        x12 = ((int) (short) ld16((x1 + 2468))) & 0xffffffffL; // 72b78: ldrsh w12, [x1, #0x9a4]
        { int fa = (int) x25, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72b7c: cmp w25, wzr
        x16 = ((int) x25 + (int) (32768)) & 0xffffffffL; // 72b80: add w16, w25, #0x8, lsl #12
        x10 = (n != v) ? x16 : x25; // 72b84: csel x10, x16, x25, lt
        x28 = (32767) & 0xffffffffL; // 72b88: mov w28, #0x7fff
        x19 = ((int) x10 & (int) (32767)) & 0xffffffffL; // 72b8c: and w19, w10, #0x7fff
        { int fa = (int) x10, fb = (int) ((int) x28); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72b90: cmp w10, w28
        x4 = (c && !z) ? x19 : x10; // 72b94: csel x4, x19, x10, hi
        if ((x12 & (1L << 31)) == 0) { return 0x72c60; } throw new IllegalStateException("procDsp2: 0x72b9c");
    }

    private int b72c60() {
        x25 = (ld32((x0 + 15736))) & 0xffffffffL; // 72c60: ldr w25, [x0, #0x3d78]
        x24 = ((int) x13 << 5) & 0xffffffffL; // 72c64: lsl w24, w13, #5
        x28 = x0 + ((x11 & 0xffffffffL) << 2); // 72c68: add x28, x0, w11, uxtw #2
        x16 = x28 + 1093632; // 72c6c: add x16, x28, #0x10b, lsl #12
        x9 = x0 + ((x27 & 0xffffffffL) << 2); // 72c70: add x9, x0, w27, uxtw #2
        x27 = x9 + 1093632; // 72c74: add x27, x9, #0x10b, lsl #12
        x24 = (long) (int) x25 * (long) (int) x24; // 72c78: smull x24, w25, w24
        x10 = x0 + ((x26 & 0xffffffffL) << 2); // 72c7c: add x10, x0, w26, uxtw #2
        v31l = x16; v31h = 0; // 72c80: fmov d31, x16
        x26 = x10 + 1093632; // 72c84: add x26, x10, #0x10b, lsl #12
        v21l = x27; v21h = 0; // 72c88: fmov d21, x27
        x11 = ((int) x11 - (int) ((int) x12)) & 0xffffffffL; // 72c8c: sub w11, w11, w12
        v24l = x24; v24h = 0; // 72c90: fmov d24, x24
        x4 = x4 + 270336; // 72c94: add x4, x4, #0x42, lsl #12
        v23l = x26; v23h = 0; // 72c98: fmov d23, x26
        x10 = x10 + 1224704; // 72c9c: add x10, x10, #0x12b, lsl #12
        x24 = v31l; // 72ca0: fmov x24, d31
        x26 = x4 + 3220; // 72ca4: add x26, x4, #0xc94
        x12 = v21l; // 72ca8: fmov x12, d21
        x28 = x28 + 1224704; // 72cac: add x28, x28, #0x12b, lsl #12
        v0l = v24l >> 15; v0h = 0; // 72cb0: sshr d0, d24, #0xf
        x9 = x9 + 1224704; // 72cb4: add x9, x9, #0x12b, lsl #12
        { int fa = (int) x11, fb = (int) (0); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72cb8: cmp w11, wzr
        x4 = ((int) x11 + (int) (32768)) & 0xffffffffL; // 72cbc: add w4, w11, #0x8, lsl #12
        x27 = x0 + ((x7 & 0xffffffffL) << 2); // 72cc0: add x27, x0, w7, uxtw #2
        x11 = ((n != v) ? (int) x4 : (int) x11) & 0xffffffffL; // 72cc4: csel w11, w4, w11, lt
        v28l = x10; v28h = 0; // 72cc8: fmov d28, x10
        x10 = (32767) & 0xffffffffL; // 72ccc: mov w10, #0x7fff
        st32((x24 + 592), (int) v0l); // 72cd0: str s0, [x24, #0x250]
        x16 = x27 + 1224704; // 72cd4: add x16, x27, #0x12b, lsl #12
        { int fa = (int) x11, fb = (int) ((int) x10); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 72cd8: cmp w11, w10
        x6 = x0 + ((x6 & 0xffffffffL) << 2); // 72cdc: add x6, x0, w6, uxtw #2
        v30l = x28; v30h = 0; // 72ce0: fmov d30, x28
        x4 = (ld32((x0 + 15740))) & 0xffffffffL; // 72ce4: ldr w4, [x0, #0x3d7c]
        v26l = x9; v26h = 0; // 72ce8: fmov d26, x9
        x9 = x27 + 1355776; // 72cec: add x9, x27, #0x14b, lsl #12
        x28 = v23l; // 72cf0: fmov x28, d23
        x27 = (ld32((x12 + 592))) & 0xffffffffL; // 72cf4: ldr w27, [x12, #0x250]
        x12 = ((int) x11 & (int) ((int) x10)) & 0xffffffffL; // 72cf8: and w12, w11, w10
        x19 = x6 + 1224704; // 72cfc: add x19, x6, #0x12b, lsl #12
        x10 = ((int) v0l) & 0xffffffffL; // 72d00: fmov w10, s0
        x11 = ((c && !z) ? (int) x12 : (int) x11) & 0xffffffffL; // 72d04: csel w11, w12, w11, hi
        x12 = (ld32((x0 + 15744))) & 0xffffffffL; // 72d08: ldr w12, [x0, #0x3d80]
        x11 = x11 + 303104; // 72d0c: add x11, x11, #0x4a, lsl #12
        x28 = (ld32((x28 + 592))) & 0xffffffffL; // 72d10: ldr w28, [x28, #0x250]
        x25 = x5 + 1224704; // 72d14: add x25, x5, #0x12b, lsl #12
        v25l = x26; v25h = 0; // 72d18: fmov d25, x26
        x7 = x0 + 16384; // 72d1c: add x7, x0, #0x4, lsl #12
        x10 = (long) (int) x4 * (long) (int) x10; // 72d20: smull x10, w4, w10
        x4 = (ld32((x0 + 15748))) & 0xffffffffL; // 72d24: ldr w4, [x0, #0x3d84]
        x26 = ((int) x14 << 5) & 0xffffffffL; // 72d28: lsl w26, w14, #5
        x27 = (long) (int) x12 * (long) (int) x27; // 72d2c: smull x27, w12, w27
        v1l = x10; v1h = 0; // 72d30: fmov d1, x10
        x6 = x6 + 1355776; // 72d34: add x6, x6, #0x14b, lsl #12
        x12 = (long) (int) x4 * (long) (int) x28; // 72d38: smull x12, w4, w28
        x4 = (ld32((x19 + 592))) & 0xffffffffL; // 72d3c: ldr w4, [x19, #0x250]
        x10 = x27 >> 15; // 72d40: asr x10, x27, #15
        x28 = (ld32((x0 + 15752))) & 0xffffffffL; // 72d44: ldr w28, [x0, #0x3d88]
        x24 = x5 + 1355776; // 72d48: add x24, x5, #0x14b, lsl #12
        x27 = (ld32((x16 + 592))) & 0xffffffffL; // 72d4c: ldr w27, [x16, #0x250]
        v3l = v1l >> 15; v3h = 0; // 72d50: sshr d3, d1, #0xf
        x28 = (long) (int) x28 * (long) (int) x4; // 72d54: smull x28, w28, w4
        x4 = (ld32((x0 + 15756))) & 0xffffffffL; // 72d58: ldr w4, [x0, #0x3d8c]
        v29l = x28; v29h = 0; // 72d5c: fmov d29, x28
        x28 = (long) (int) x4 * (long) (int) x27; // 72d60: smull x28, w4, w27
        x4 = x11 + 3444; // 72d64: add x4, x11, #0xd74
        x27 = x12 >> 15; // 72d68: asr x27, x12, #15
        x11 = (65535) & 0xffffffffL; // 72d6c: mov w11, #0xffff
        x28 = x28 >> 15; // 72d70: asr x28, x28, #15
        v27l = x4; v27h = 0; // 72d74: fmov d27, x4
        x4 = ((int) v3l) & 0xffffffffL; // 72d78: fmov w4, s3
        x12 = v29l; // 72d7c: fmov x12, d29
        v4l = x11 & 0xffffffffL; v4h = 0; // 72d80: fmov s4, w11
        x10 = ((int) x4 + (int) ((int) x10)) & 0xffffffffL; // 72d84: add w10, w4, w10
        x27 = ((int) x10 + (int) ((int) x27)) & 0xffffffffL; // 72d88: add w27, w10, w27
        x12 = x12 >> 15; // 72d8c: asr x12, x12, #15
        x12 = ((int) x27 + (int) ((int) x12)) & 0xffffffffL; // 72d90: add w12, w27, w12
        x11 = ((int) x12 + (int) ((int) x28)) & 0xffffffffL; // 72d94: add w11, w12, w28
        st32((x25 + 592), (int) x11); // 72d98: str w11, [x25, #0x250]
        x28 = (ld32((x0 + 15760))) & 0xffffffffL; // 72d9c: ldr w28, [x0, #0x3d90]
        x4 = (ld32((x19 + 592))) & 0xffffffffL; // 72da0: ldr w4, [x19, #0x250]
        x10 = (ld32((x0 + 15764))) & 0xffffffffL; // 72da4: ldr w10, [x0, #0x3d94]
        x12 = (ld32((x0 + 15768))) & 0xffffffffL; // 72da8: ldr w12, [x0, #0x3d98]
        x11 = (long) (int) x28 * (long) (int) x11; // 72dac: smull x11, w28, w11
        x28 = (ld32((x19 + 720))) & 0xffffffffL; // 72db0: ldr w28, [x19, #0x2d0]
        x27 = (long) (int) x10 * (long) (int) x4; // 72db4: smull x27, w10, w4
        x10 = x11 >> 15; // 72db8: asr x10, x11, #15
        x12 = (long) (int) x12 * (long) (int) x28; // 72dbc: smull x12, w12, w28
        x11 = x27 >> 15; // 72dc0: asr x11, x27, #15
        x28 = v25l; // 72dc4: fmov x28, d25
        x4 = x12 >> 15; // 72dc8: asr x4, x12, #15
        x27 = ((int) x10 + (int) ((int) x11)) & 0xffffffffL; // 72dcc: add w27, w10, w11
        x10 = ((int) x27 + (int) ((int) x4)) & 0xffffffffL; // 72dd0: add w10, w27, w4
        st32((x25 + 720), (int) x10); // 72dd4: str w10, [x25, #0x2d0]
        x4 = (ld32((x0 + (x28 << 2)))) & 0xffffffffL; // 72dd8: ldr w4, [x0, x28, lsl #2]
        st32((x25 + 848), (int) x4); // 72ddc: str w4, [x25, #0x350]
        x28 = (ld32((x0 + 15772))) & 0xffffffffL; // 72de0: ldr w28, [x0, #0x3d9c]
        x27 = (ld32((x0 + 15776))) & 0xffffffffL; // 72de4: ldr w27, [x0, #0x3da0]
        x12 = (ld32((x0 + 15780))) & 0xffffffffL; // 72de8: ldr w12, [x0, #0x3da4]
        x28 = (long) (int) x28 * (long) (int) x4; // 72dec: smull x28, w28, w4
        x4 = (ld32((x19 + 848))) & 0xffffffffL; // 72df0: ldr w4, [x19, #0x350]
        x11 = (ld32((x0 + 15784))) & 0xffffffffL; // 72df4: ldr w11, [x0, #0x3da8]
        v5l = x28; v5h = 0; // 72df8: fmov d5, x28
        x27 = (long) (int) x27 * (long) (int) x4; // 72dfc: smull x27, w27, w4
        x4 = (ld32((x16 + 848))) & 0xffffffffL; // 72e00: ldr w4, [x16, #0x350]
        x28 = x27 >> 15; // 72e04: asr x28, x27, #15
        x27 = (ld32((x16 + 976))) & 0xffffffffL; // 72e08: ldr w27, [x16, #0x3d0]
        v2l = v5l >> 15; v2h = 0; // 72e0c: sshr d2, d5, #0xf
        x12 = (long) (int) x12 * (long) (int) x4; // 72e10: smull x12, w12, w4
        x4 = (ld32((x19 + 976))) & 0xffffffffL; // 72e14: ldr w4, [x19, #0x3d0]
        x11 = (long) (int) x11 * (long) (int) x4; // 72e18: smull x11, w11, w4
        x4 = (ld32((x0 + 15788))) & 0xffffffffL; // 72e1c: ldr w4, [x0, #0x3dac]
        x4 = (long) (int) x4 * (long) (int) x27; // 72e20: smull x4, w4, w27
        x27 = x12 >> 15; // 72e24: asr x27, x12, #15
        x12 = x11 >> 15; // 72e28: asr x12, x11, #15
        x11 = x4 >> 15; // 72e2c: asr x11, x4, #15
        x4 = ((int) v2l) & 0xffffffffL; // 72e30: fmov w4, s2
        x28 = ((int) x4 + (int) ((int) x28)) & 0xffffffffL; // 72e34: add w28, w4, w28
        x27 = ((int) x28 + (int) ((int) x27)) & 0xffffffffL; // 72e38: add w27, w28, w27
        x12 = ((int) x27 + (int) ((int) x12)) & 0xffffffffL; // 72e3c: add w12, w27, w12
        x11 = ((int) x12 + (int) ((int) x11)) & 0xffffffffL; // 72e40: add w11, w12, w11
        st32((x25 + 976), (int) x11); // 72e44: str w11, [x25, #0x3d0]
        x12 = (ld32((x16 + 976))) & 0xffffffffL; // 72e48: ldr w12, [x16, #0x3d0]
        x28 = (ld32((x0 + 15792))) & 0xffffffffL; // 72e4c: ldr w28, [x0, #0x3db0]
        x27 = (ld32((x0 + 15796))) & 0xffffffffL; // 72e50: ldr w27, [x0, #0x3db4]
        x4 = (ld32((x16 + 1104))) & 0xffffffffL; // 72e54: ldr w4, [x16, #0x450]
        x16 = (ld32((x19 + 976))) & 0xffffffffL; // 72e58: ldr w16, [x19, #0x3d0]
        x11 = (long) (int) x28 * (long) (int) x11; // 72e5c: smull x11, w28, w11
        x28 = (long) (int) x27 * (long) (int) x16; // 72e60: smull x28, w27, w16
        x27 = (ld32((x0 + 15800))) & 0xffffffffL; // 72e64: ldr w27, [x0, #0x3db8]
        x16 = (ld32((x19 + 1104))) & 0xffffffffL; // 72e68: ldr w16, [x19, #0x450]
        x27 = (long) (int) x27 * (long) (int) x12; // 72e6c: smull x27, w27, w12
        x12 = (ld32((x0 + 15804))) & 0xffffffffL; // 72e70: ldr w12, [x0, #0x3dbc]
        x12 = (long) (int) x12 * (long) (int) x16; // 72e74: smull x12, w12, w16
        x16 = x11 >> 15; // 72e78: asr x16, x11, #15
        x11 = x28 >> 15; // 72e7c: asr x11, x28, #15
        x28 = (ld32((x0 + 15808))) & 0xffffffffL; // 72e80: ldr w28, [x0, #0x3dc0]
        x16 = ((int) x16 + (int) ((int) x11)) & 0xffffffffL; // 72e84: add w16, w16, w11
        x4 = (long) (int) x28 * (long) (int) x4; // 72e88: smull x4, w28, w4
        x28 = x27 >> 15; // 72e8c: asr x28, x27, #15
        x27 = x12 >> 15; // 72e90: asr x27, x12, #15
        x11 = ((int) x16 + (int) ((int) x28)) & 0xffffffffL; // 72e94: add w11, w16, w28
        x12 = x4 >> 15; // 72e98: asr x12, x4, #15
        x4 = ((int) x11 + (int) ((int) x27)) & 0xffffffffL; // 72e9c: add w4, w11, w27
        x16 = ((int) x4 + (int) ((int) x12)) & 0xffffffffL; // 72ea0: add w16, w4, w12
        st32((x25 + 1104), (int) x16); // 72ea4: str w16, [x25, #0x450]
        x11 = (ld32((x19 + 1104))) & 0xffffffffL; // 72ea8: ldr w11, [x19, #0x450]
        x27 = (ld32((x0 + 15812))) & 0xffffffffL; // 72eac: ldr w27, [x0, #0x3dc4]
        x12 = (ld32((x0 + 15816))) & 0xffffffffL; // 72eb0: ldr w12, [x0, #0x3dc8]
        x28 = (ld32((x19 + 1232))) & 0xffffffffL; // 72eb4: ldr w28, [x19, #0x4d0]
        x4 = (ld32((x0 + 15820))) & 0xffffffffL; // 72eb8: ldr w4, [x0, #0x3dcc]
        x16 = (long) (int) x27 * (long) (int) x16; // 72ebc: smull x16, w27, w16
        x27 = (long) (int) x12 * (long) (int) x11; // 72ec0: smull x27, w12, w11
        x12 = (long) (int) x4 * (long) (int) x28; // 72ec4: smull x12, w4, w28
        x4 = x16 >> 15; // 72ec8: asr x4, x16, #15
        x28 = x27 >> 15; // 72ecc: asr x28, x27, #15
        x11 = ((int) x4 + (int) ((int) x28)) & 0xffffffffL; // 72ed0: add w11, w4, w28
        x16 = x12 >> 15; // 72ed4: asr x16, x12, #15
        x27 = ((int) x11 + (int) ((int) x16)) & 0xffffffffL; // 72ed8: add w27, w11, w16
        st32((x25 + 1232), (int) x27); // 72edc: str w27, [x25, #0x4d0]
        x28 = ((int) v0l) & 0xffffffffL; // 72ee0: fmov w28, s0
        x12 = (ld32((x7 + 384))) & 0xffffffffL; // 72ee4: ldr w12, [x7, #0x180]
        x4 = (ld32((x7 + 388))) & 0xffffffffL; // 72ee8: ldr w4, [x7, #0x184]
        x19 = (ld32((x19 + 1360))) & 0xffffffffL; // 72eec: ldr w19, [x19, #0x550]
        x16 = (long) (int) x28 * (long) (int) x12; // 72ef0: smull x16, w28, w12
        x28 = v30l; // 72ef4: fmov x28, d30
        x11 = (long) (int) x4 * (long) (int) x19; // 72ef8: smull x11, w4, w19
        x12 = x16 >> 15; // 72efc: asr x12, x16, #15
        x4 = x11 >> 15; // 72f00: asr x4, x11, #15
        x19 = ((int) x12 + (int) ((int) x4)) & 0xffffffffL; // 72f04: add w19, w12, w4
        st32((x25 + 1360), (int) x19); // 72f08: str w19, [x25, #0x550]
        x11 = v26l; // 72f0c: fmov x11, d26
        x25 = (ld32((x0 + 15844))) & 0xffffffffL; // 72f10: ldr w25, [x0, #0x3de4]
        x12 = v28l; // 72f14: fmov x12, d28
        v20l = x19 & 0xffffffffL; v20h = 0; // 72f18: fmov s20, w19
        x26 = (long) (int) x25 * (long) (int) x26; // 72f1c: smull x26, w25, w26
        x16 = x26 >> 15; // 72f20: asr x16, x26, #15
        st32((x28 + 1488), (int) x16); // 72f24: str w16, [x28, #0x5d0]
        x26 = (ld32((x0 + 15848))) & 0xffffffffL; // 72f28: ldr w26, [x0, #0x3de8]
        x4 = (ld32((x11 + 1488))) & 0xffffffffL; // 72f2c: ldr w4, [x11, #0x5d0]
        x28 = (ld32((x6 + 1488))) & 0xffffffffL; // 72f30: ldr w28, [x6, #0x5d0]
        x11 = (ld32((x12 + 1488))) & 0xffffffffL; // 72f34: ldr w11, [x12, #0x5d0]
        x25 = (ld32((x0 + 15852))) & 0xffffffffL; // 72f38: ldr w25, [x0, #0x3dec]
        x12 = (ld32((x0 + 15860))) & 0xffffffffL; // 72f3c: ldr w12, [x0, #0x3df4]
        x19 = (ld32((x0 + 15856))) & 0xffffffffL; // 72f40: ldr w19, [x0, #0x3df0]
        x26 = (long) (int) x26 * (long) (int) x16; // 72f44: smull x26, w26, w16
        x25 = (long) (int) x25 * (long) (int) x4; // 72f48: smull x25, w25, w4
        x12 = (long) (int) x12 * (long) (int) x28; // 72f4c: smull x12, w12, w28
        x4 = (ld32((x0 + 15864))) & 0xffffffffL; // 72f50: ldr w4, [x0, #0x3df8]
        x28 = x26 >> 15; // 72f54: asr x28, x26, #15
        x26 = (ld32((x9 + 1488))) & 0xffffffffL; // 72f58: ldr w26, [x9, #0x5d0]
        x19 = (long) (int) x19 * (long) (int) x11; // 72f5c: smull x19, w19, w11
        x11 = x25 >> 15; // 72f60: asr x11, x25, #15
        x4 = (long) (int) x4 * (long) (int) x26; // 72f64: smull x4, w4, w26
        x25 = x19 >> 15; // 72f68: asr x25, x19, #15
        x28 = ((int) x28 + (int) ((int) x11)) & 0xffffffffL; // 72f6c: add w28, w28, w11
        x11 = ((int) x28 + (int) ((int) x25)) & 0xffffffffL; // 72f70: add w11, w28, w25
        x19 = x12 >> 15; // 72f74: asr x19, x12, #15
        x26 = ((int) x11 + (int) ((int) x19)) & 0xffffffffL; // 72f78: add w26, w11, w19
        x12 = x4 >> 15; // 72f7c: asr x12, x4, #15
        x28 = ((int) x26 + (int) ((int) x12)) & 0xffffffffL; // 72f80: add w28, w26, w12
        st32((x24 + 1488), (int) x28); // 72f84: str w28, [x24, #0x5d0]
        x26 = x24; // 72f88: mov x26, x24
        x19 = (ld32((x0 + 15868))) & 0xffffffffL; // 72f8c: ldr w19, [x0, #0x3dfc]
        x24 = (ld32((x6 + 1488))) & 0xffffffffL; // 72f90: ldr w24, [x6, #0x5d0]
        x12 = (ld32((x0 + 15872))) & 0xffffffffL; // 72f94: ldr w12, [x0, #0x3e00]
        x4 = (ld32((x0 + 15876))) & 0xffffffffL; // 72f98: ldr w4, [x0, #0x3e04]
        x25 = (ld32((x6 + 1616))) & 0xffffffffL; // 72f9c: ldr w25, [x6, #0x650]
        x11 = (long) (int) x19 * (long) (int) x28; // 72fa0: smull x11, w19, w28
        x28 = (long) (int) x12 * (long) (int) x24; // 72fa4: smull x28, w12, w24
        x19 = (long) (int) x4 * (long) (int) x25; // 72fa8: smull x19, w4, w25
        x24 = x11 >> 15; // 72fac: asr x24, x11, #15
        x4 = x28 >> 15; // 72fb0: asr x4, x28, #15
        x11 = v27l; // 72fb4: fmov x11, d27
        x12 = x19 >> 15; // 72fb8: asr x12, x19, #15
        x25 = ((int) x24 + (int) ((int) x4)) & 0xffffffffL; // 72fbc: add w25, w24, w4
        x24 = ((int) x25 + (int) ((int) x12)) & 0xffffffffL; // 72fc0: add w24, w25, w12
        st32((x26 + 1616), (int) x24); // 72fc4: str w24, [x26, #0x650]
        x4 = (ld32((x0 + (x11 << 2)))) & 0xffffffffL; // 72fc8: ldr w4, [x0, x11, lsl #2]
        st32((x26 + 1744), (int) x4); // 72fcc: str w4, [x26, #0x6d0]
        x25 = (ld32((x0 + 15880))) & 0xffffffffL; // 72fd0: ldr w25, [x0, #0x3e08]
        x28 = (ld32((x9 + 1744))) & 0xffffffffL; // 72fd4: ldr w28, [x9, #0x6d0]
        x12 = (ld32((x0 + 15888))) & 0xffffffffL; // 72fd8: ldr w12, [x0, #0x3e10]
        x19 = (ld32((x0 + 15884))) & 0xffffffffL; // 72fdc: ldr w19, [x0, #0x3e0c]
        x25 = (long) (int) x25 * (long) (int) x4; // 72fe0: smull x25, w25, w4
        x4 = (ld32((x6 + 1744))) & 0xffffffffL; // 72fe4: ldr w4, [x6, #0x6d0]
        x11 = (ld32((x0 + 15892))) & 0xffffffffL; // 72fe8: ldr w11, [x0, #0x3e14]
        x12 = (long) (int) x12 * (long) (int) x28; // 72fec: smull x12, w12, w28
        x28 = (ld32((x6 + 1872))) & 0xffffffffL; // 72ff0: ldr w28, [x6, #0x750]
        x19 = (long) (int) x19 * (long) (int) x4; // 72ff4: smull x19, w19, w4
        x4 = (ld32((x0 + 15896))) & 0xffffffffL; // 72ff8: ldr w4, [x0, #0x3e18]
        x11 = (long) (int) x11 * (long) (int) x28; // 72ffc: smull x11, w11, w28
        return 0x73000;
    }

    private int b73000() {
        x28 = x25 >> 15; // 73000: asr x28, x25, #15
        x25 = x19 >> 15; // 73004: asr x25, x19, #15
        x19 = (ld32((x9 + 1872))) & 0xffffffffL; // 73008: ldr w19, [x9, #0x750]
        x28 = ((int) x28 + (int) ((int) x25)) & 0xffffffffL; // 7300c: add w28, w28, w25
        x4 = (long) (int) x4 * (long) (int) x19; // 73010: smull x4, w4, w19
        x19 = x12 >> 15; // 73014: asr x19, x12, #15
        x12 = x11 >> 15; // 73018: asr x12, x11, #15
        x25 = ((int) x28 + (int) ((int) x19)) & 0xffffffffL; // 7301c: add w25, w28, w19
        x11 = x4 >> 15; // 73020: asr x11, x4, #15
        x4 = ((int) x25 + (int) ((int) x12)) & 0xffffffffL; // 73024: add w4, w25, w12
        x28 = ((int) x4 + (int) ((int) x11)) & 0xffffffffL; // 73028: add w28, w4, w11
        st32((x26 + 1872), (int) x28); // 7302c: str w28, [x26, #0x750]
        x11 = (ld32((x9 + 1872))) & 0xffffffffL; // 73030: ldr w11, [x9, #0x750]
        x25 = (ld32((x0 + 15900))) & 0xffffffffL; // 73034: ldr w25, [x0, #0x3e1c]
        x19 = (ld32((x0 + 15904))) & 0xffffffffL; // 73038: ldr w19, [x0, #0x3e20]
        x4 = (ld32((x9 + 2000))) & 0xffffffffL; // 7303c: ldr w4, [x9, #0x7d0]
        x9 = (ld32((x6 + 1872))) & 0xffffffffL; // 73040: ldr w9, [x6, #0x750]
        x12 = (ld32((x0 + 15908))) & 0xffffffffL; // 73044: ldr w12, [x0, #0x3e24]
        x28 = (long) (int) x25 * (long) (int) x28; // 73048: smull x28, w25, w28
        x25 = (long) (int) x19 * (long) (int) x9; // 7304c: smull x25, w19, w9
        x12 = (long) (int) x12 * (long) (int) x11; // 73050: smull x12, w12, w11
        x19 = (ld32((x0 + 15912))) & 0xffffffffL; // 73054: ldr w19, [x0, #0x3e28]
        x9 = x25 >> 15; // 73058: asr x9, x25, #15
        x11 = (ld32((x6 + 2000))) & 0xffffffffL; // 7305c: ldr w11, [x6, #0x7d0]
        x28 = x28 >> 15; // 73060: asr x28, x28, #15
        x25 = (ld32((x0 + 15916))) & 0xffffffffL; // 73064: ldr w25, [x0, #0x3e2c]
        x28 = ((int) x28 + (int) ((int) x9)) & 0xffffffffL; // 73068: add w28, w28, w9
        x11 = (long) (int) x19 * (long) (int) x11; // 7306c: smull x11, w19, w11
        x4 = (long) (int) x25 * (long) (int) x4; // 73070: smull x4, w25, w4
        x19 = x12 >> 15; // 73074: asr x19, x12, #15
        x12 = x11 >> 15; // 73078: asr x12, x11, #15
        x9 = ((int) x28 + (int) ((int) x19)) & 0xffffffffL; // 7307c: add w9, w28, w19
        x11 = x4 >> 15; // 73080: asr x11, x4, #15
        x25 = ((int) x9 + (int) ((int) x12)) & 0xffffffffL; // 73084: add w25, w9, w12
        x28 = ((int) x25 + (int) ((int) x11)) & 0xffffffffL; // 73088: add w28, w25, w11
        st32((x26 + 2000), (int) x28); // 7308c: str w28, [x26, #0x7d0]
        x9 = (ld32((x6 + 2000))) & 0xffffffffL; // 73090: ldr w9, [x6, #0x7d0]
        x12 = (ld32((x0 + 15920))) & 0xffffffffL; // 73094: ldr w12, [x0, #0x3e30]
        x11 = (ld32((x0 + 15924))) & 0xffffffffL; // 73098: ldr w11, [x0, #0x3e34]
        x19 = (ld32((x6 + 2128))) & 0xffffffffL; // 7309c: ldr w19, [x6, #0x850]
        x4 = (ld32((x0 + 15928))) & 0xffffffffL; // 730a0: ldr w4, [x0, #0x3e38]
        x25 = (long) (int) x11 * (long) (int) x9; // 730a4: smull x25, w11, w9
        x28 = (long) (int) x12 * (long) (int) x28; // 730a8: smull x28, w12, w28
        x9 = (long) (int) x4 * (long) (int) x19; // 730ac: smull x9, w4, w19
        x11 = x28 >> 15; // 730b0: asr x11, x28, #15
        x12 = x25 >> 15; // 730b4: asr x12, x25, #15
        x19 = ((int) x11 + (int) ((int) x12)) & 0xffffffffL; // 730b8: add w19, w11, w12
        x4 = x9 >> 15; // 730bc: asr x4, x9, #15
        x28 = ((int) x19 + (int) ((int) x4)) & 0xffffffffL; // 730c0: add w28, w19, w4
        st32((x26 + 2128), (int) x28); // 730c4: str w28, [x26, #0x850]
        x25 = (ld32((x7 + 392))) & 0xffffffffL; // 730c8: ldr w25, [x7, #0x188]
        x6 = (ld32((x6 + 2256))) & 0xffffffffL; // 730cc: ldr w6, [x6, #0x8d0]
        x7 = (ld32((x7 + 396))) & 0xffffffffL; // 730d0: ldr w7, [x7, #0x18c]
        x9 = (long) (int) x16 * (long) (int) x25; // 730d4: smull x9, w16, w25
        x11 = (long) (int) x7 * (long) (int) x6; // 730d8: smull x11, w7, w6
        x12 = x9 >> 15; // 730dc: asr x12, x9, #15
        x4 = x11 >> 15; // 730e0: asr x4, x11, #15
        x7 = ((int) v0l) & 0xffffffffL; // 730e4: fmov w7, s0
        x9 = ((int) x12 + (int) ((int) x4)) & 0xffffffffL; // 730e8: add w9, w12, w4
        st32((x26 + 2256), (int) x9); // 730ec: str w9, [x26, #0x8d0]
        x25 = (ld32((x0 + 15824))) & 0xffffffffL; // 730f0: ldr w25, [x0, #0x3dd0]
        x6 = (ld32((x0 + 15836))) & 0xffffffffL; // 730f4: ldr w6, [x0, #0x3ddc]
        x26 = (ld32((x0 + 15828))) & 0xffffffffL; // 730f8: ldr w26, [x0, #0x3dd4]
        x12 = (long) (int) x7 * (long) (int) x25; // 730fc: smull x12, w7, w25
        x19 = (ld32((x0 + 15832))) & 0xffffffffL; // 73100: ldr w19, [x0, #0x3dd8]
        x28 = (long) (int) x6 * (long) (int) x28; // 73104: smull x28, w6, w28
        x6 = x12 >> 15; // 73108: asr x6, x12, #15
        x4 = (ld32((x0 + 15840))) & 0xffffffffL; // 7310c: ldr w4, [x0, #0x3de0]
        x12 = ((int) v4l) & 0xffffffffL; // 73110: fmov w12, s4
        x11 = (long) (int) x26 * (long) (int) x10; // 73114: smull x11, w26, w10
        x25 = (long) (int) x19 * (long) (int) x24; // 73118: smull x25, w19, w24
        x26 = x11 >> 15; // 7311c: asr x26, x11, #15
        x19 = ((int) x4 + (int) (32768)) & 0xffffffffL; // 73120: add w19, w4, #0x8, lsl #12
        x7 = x25 >> 15; // 73124: asr x7, x25, #15
        { int fa = (int) x19, fb = (int) ((int) x12); int fr = fa - fb; n = fr < 0; z = fr == 0; c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; } // 73128: cmp w19, w12
        x25 = ((int) x26 + (int) ((int) x6)) & 0xffffffffL; // 7312c: add w25, w26, w6
        x11 = x28 >> 15; // 73130: asr x11, x28, #15
        x28 = ((int) x25 + (int) ((int) x7)) & 0xffffffffL; // 73134: add w28, w25, w7
        x26 = ((int) x28 + (int) ((int) x11)) & 0xffffffffL; // 73138: add w26, w28, w11
        if (c && !z) { return 0x731f4; } throw new IllegalStateException("procDsp2: 0x73140");
    }

    private int b73158() {
        x28 = (ld32((x0 + 15932))) & 0xffffffffL; // 73158: ldr w28, [x0, #0x3e3c]
        x4 = ((int) x19 >> 5) & 0xffffffffL; // 7315c: asr w4, w19, #5
        x26 = (ld32((x0 + 15936))) & 0xffffffffL; // 73160: ldr w26, [x0, #0x3e40]
        x11 = (ld32((x0 + 15940))) & 0xffffffffL; // 73164: ldr w11, [x0, #0x3e44]
        x25 = ((int) v20l) & 0xffffffffL; // 73168: fmov w25, s20
        x7 = (ld32((x0 + 15944))) & 0xffffffffL; // 7316c: ldr w7, [x0, #0x3e48]
        x24 = (long) (int) x24 * (long) (int) x26; // 73170: smull x24, w24, w26
        x6 = (long) (int) x16 * (long) (int) x28; // 73174: smull x6, w16, w28
        x10 = (long) (int) x10 * (long) (int) x11; // 73178: smull x10, w10, w11
        x27 = (long) (int) x7 * (long) (int) x27; // 7317c: smull x27, w7, w27
        x19 = x24 >> 15; // 73180: asr x19, x24, #15
        x28 = x6 >> 15; // 73184: asr x28, x6, #15
        x9 = ((int) x25 + (int) ((int) x9)) & 0xffffffffL; // 73188: add w9, w25, w9
        x26 = (ld32((x0 + 14048))) & 0xffffffffL; // 7318c: ldr w26, [x0, #0x36e0]
        x7 = ((int) v0l) & 0xffffffffL; // 73190: fmov w7, s0
        x25 = ((int) x9 >> 5) & 0xffffffffL; // 73194: asr w25, w9, #5
        x12 = x10 >> 15; // 73198: asr x12, x10, #15
        x9 = (ld32((x0 + 14044))) & 0xffffffffL; // 7319c: ldr w9, [x0, #0x36dc]
        x10 = ((int) x19 + (int) ((int) x28)) & 0xffffffffL; // 731a0: add w10, w19, w28
        x24 = (ld32((x0 + 15948))) & 0xffffffffL; // 731a4: ldr w24, [x0, #0x3e4c]
        x11 = x27 >> 15; // 731a8: asr x11, x27, #15
        x27 = ((int) x10 + (int) ((int) x12)) & 0xffffffffL; // 731ac: add w27, w10, w12
        x19 = ((int) x27 + (int) ((int) x11)) & 0xffffffffL; // 731b0: add w19, w27, w11
        st32((x2 + 1104), (int) x25); // 731b4: str w25, [x2, #0x450]
        x6 = (long) (int) x7 * (long) (int) x9; // 731b8: smull x6, w7, w9
        x16 = (long) (int) x16 * (long) (int) x26; // 731bc: smull x16, w16, w26
        x9 = (long) (int) x24 * (long) (int) x19; // 731c0: smull x9, w24, w19
        x28 = x6 >> 15; // 731c4: asr x28, x6, #15
        x25 = x16 >> 15; // 731c8: asr x25, x16, #15
        st32((x2 + 2128), (int) x4); // 731cc: str w4, [x2, #0x850]
        x12 = x9 >> 15; // 731d0: asr x12, x9, #15
        x4 = ((int) x28 + (int) ((int) x25)) & 0xffffffffL; // 731d4: add w4, w28, w25
        x26 = ((int) x12 >> 5) & 0xffffffffL; // 731d8: asr w26, w12, #5
        x11 = ((int) x4 >> 5) & 0xffffffffL; // 731dc: asr w11, w4, #5
        st32((x2 + 1872), (int) x13); // 731e0: str w13, [x2, #0x750]
        st32((x2 + 2000), (int) x14); // 731e4: str w14, [x2, #0x7d0]
        st32((x2 + 2256), (int) x26); // 731e8: str w26, [x2, #0x8d0]
        st32((x2 + 976), (int) x11); // 731ec: str w11, [x2, #0x3d0]
        return 0x72500;
    }

    private int b731f4() {
        x12 = (long) (int) x4 * (long) (int) x26; // 731f4: smull x12, w4, w26
        x19 = x12 >>> 15; // 731f8: lsr x19, x12, #15
        return 0x73158;
    }

    private int b769dc() {
        st32((x3 + 3408), 0); // 769dc: str wzr, [x3, #0xd50]
        x11 = 0L; // 769e0: mov x11, #0x0
        st32((x3 + 3536), 0); // 769e4: str wzr, [x3, #0xdd0]
        return 0x729dc;
    }
}
