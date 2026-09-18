/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import java.util.ArrayList;
import java.util.List;


/**
 * The voices of the rohm sound source, the chip half of {@code rt_synth_2.dll}.
 * <p>
 * 64 voices at 44.1 kHz. A voice plays a signed 8 bit wave of the wave memory, linearly
 * interpolated, through a state variable filter (thru, low, high or band pass), and has three
 * envelopes (amplitude, filter, pitch) of two stages and a release, and a triangle lfo with a
 * delay which goes to the pitch, the filter and the amplitude.
 * <p>
 * It is driven by commands ({@link RohmDriver} makes them), which are taken at the end of the
 * block after the one they are posted in, as the dll takes them. The envelopes, the lfo and
 * the pans move once a block (128 samples), the amplitude is ramped over the block.
 * <p>
 * Everything is the integer arithmetic of the dll, 16 bit where it keeps 16 bits.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class RohmLsi {

    static final int VOICES = 64;
    /** samples of a block */
    static final int BLOCK = 128;

    /** command types */
    static final int OFF = 1, RELEASE = 2, KEY_ON = 3, CUT = 4, VOLUME = 5, PITCH = 6, MODULATION = 7;

    /** the length of a key on command */
    static final int KEY_ON_LENGTH = 41;

    // the registers of a voice (a 0x60 byte record of the dll)

    /** bit 0: sounding, bit 1: key on */
    final short[] flags = new short[VOICES];
    final short[] lfoPhase = new short[VOICES], lfoRate = new short[VOICES], lfoRateNext = new short[VOICES], lfoDelay = new short[VOICES];
    final short[] lfoPitchUp = new short[VOICES], lfoPitchDown = new short[VOICES];
    final short[] lfoFilterUp = new short[VOICES], lfoFilterDown = new short[VOICES];
    final short[] lfoAmpUp = new short[VOICES], lfoAmpDown = new short[VOICES];
    final short[] aegLevel = new short[VOICES], aegRate = new short[VOICES], aegRate2 = new short[VOICES];
    final short[] aegCount = new short[VOICES], aegTarget = new short[VOICES], aegTarget2 = new short[VOICES];
    final short[] volume = new short[VOICES];
    final short[] panL = new short[VOICES], panR = new short[VOICES];
    final short[] gainL = new short[VOICES], gainR = new short[VOICES];
    final int[] pitchBase = new int[VOICES];
    final short[] pegLevel = new short[VOICES], pegRate = new short[VOICES], pegRate2 = new short[VOICES];
    final short[] pegCount = new short[VOICES], pegTarget = new short[VOICES], pegTarget2 = new short[VOICES];
    final short[] fegLevel = new short[VOICES], fegRate = new short[VOICES], fegRate2 = new short[VOICES];
    final short[] fegCount = new short[VOICES], fegTarget = new short[VOICES], fegTarget2 = new short[VOICES];
    final short[] cutoffMin = new short[VOICES], cutoffMax = new short[VOICES], cutoffBase = new short[VOICES];
    final short[] panStepL = new short[VOICES], panStepR = new short[VOICES];
    final short[] panTargetL = new short[VOICES], panTargetR = new short[VOICES];

    // the rest of a voice

    /** the wave, in 4 bytes */
    final int[] address = new int[VOICES];
    /** 16.16 */
    final int[] loopStart = new int[VOICES], end = new int[VOICES], position = new int[VOICES], increment = new int[VOICES];
    final int[] cutoff = new int[VOICES], resonance = new int[VOICES];
    /** 0: thru, 1: low pass, 2: high pass, 3: band pass */
    final short[] filterType = new short[VOICES];
    final short[] band = new short[VOICES], low = new short[VOICES];
    /** the amplitude ramped over a block, unsigned 9.7 with a fraction of 7 bits more */
    final short[] envelope = new short[VOICES], envelopeFraction = new short[VOICES], envelopeStep = new short[VOICES];
    final short[] sample = new short[VOICES];
    /** the level of a voice for the driver, 0: done */
    final int[] status = new int[VOICES];

    /** the gain of all voices */
    int master = 0x7f00;
    /** the mix is shifted by it, a negative one is to the right */
    int shift = -6;
    /** a pan moves 0x8000 >> it a block */
    final int panShift = 5;

    /** {@link RohmRom#memory} of the sound source, the wave memory is at {@link RohmRom#WAVE_DATA} */
    private final byte[] memory;

    /** commands, the ones taken at the end of this block and the ones posted during it */
    @SuppressWarnings("unchecked")
    private final List<short[]>[] commands = new List[] { new ArrayList<>(), new ArrayList<>() };
    /** voices a note is stolen from, cut at the start of the next block */
    @SuppressWarnings("unchecked")
    private final List<Integer>[] cuts = new List[] { new ArrayList<>(), new ArrayList<>() };
    /** which of the double buffers is taken */
    int toggle;

    /** the mix of a block, 16 bit stereo */
    final short[] mix = new short[BLOCK * 2];

    RohmLsi(byte[] memory) {
        this.memory = memory;
    }

    /** all voices off (0x10003bc0) */
    void reset() {
        java.util.Arrays.fill(flags, (short) 0);
        master = 0x7f00;
        shift = -6;
    }

    /** a command, taken at the end of the next block */
    void post(short[] command) {
        commands[toggle ^ 1].add(command);
    }

    /** a voice a note is stolen from, cut at the start of the next block */
    void postCut(int voice) {
        cuts[toggle ^ 1].add(voice);
    }

    /** drops what has not been taken (0x100046d0) */
    void clearQueues() {
        for (int i = 0; i < 2; i++) {
            commands[i].clear();
            cuts[i].clear();
        }
    }

    /** a block into {@link #mix} and {@link #status} (0x10004290), the buffers are turned over */
    void render() {
        cut();
        control();
        for (int i = 0; i < BLOCK; i++) {
            tick(i * 2);
        }
        execute();
        updateStatus();
        toggle ^= 1;
    }

    /** 0x10003cf0 */
    private void cut() {
        List<Integer> queue = cuts[toggle];
        for (int v : queue) {
            flags[v] &= ~2;
            aegRate[v] = 0x7fff;
            aegTarget[v] = 0;
            aegCount[v] = -1;
        }
        queue.clear();
    }

    /** the envelopes, the lfo and the pans (0x10003580) */
    private void control() {
        java.util.Arrays.fill(sample, (short) 0);
        for (int v = 0; v < VOICES; v++) {
            int flag = flags[v] & 0xffff;
            if (flag == 0) continue;

            lfoPhase[v] += lfoRate[v];
            int x = lfoPhase[v] & 0xffff;
            if ((x & 0x8000) != 0) x = ~x;
            long t = (short) (x - 0x4000);
            int pitchMod, filterMod, ampMod;
            if (t > 0) {
                pitchMod = (int) ((lfoPitchUp[v] * t) >> 14);
                filterMod = (int) ((lfoFilterUp[v] * t) >> 14);
                ampMod = (int) ((lfoAmpUp[v] * t) >> 14);
            } else {
                pitchMod = (int) ((lfoPitchDown[v] * t) >> 14);
                filterMod = (int) ((lfoFilterDown[v] * t) >> 14);
                ampMod = (int) ((lfoAmpDown[v] * t) >> 14);
            }
            pitchMod += 0x2000;
            filterMod += 0x2000;
            ampMod += 0x2000;

            // the filter, the level before the step
            int feg = fegLevel[v];
            fegLevel[v] = (short) ((int) (((long) (short) (fegTarget[v] - feg) * fegRate[v]) >> 15) + feg);
            int c = (int) (((long) cutoffBase[v] * (short) filterMod) >> 13);
            c = (int) (((long) c * feg) >> 13);
            if (c >= cutoffMax[v]) c = cutoffMax[v];
            if (c <= cutoffMin[v]) c = cutoffMin[v];
            cutoff[v] = c;

            // the pitch, the level before the step
            int peg = pegLevel[v];
            pegLevel[v] = (short) ((int) (((long) (short) (pegTarget[v] - peg) * pegRate[v]) >> 15) + peg);
            int i = (int) (((long) pitchBase[v] * (short) pitchMod) >> 13);
            increment[v] = (int) (((long) i * peg) >> 13);

            // the amplitude, the level after the step
            int aeg = aegLevel[v];
            aeg += (int) (((long) (short) (aegTarget[v] - aeg) * aegRate[v]) >> 15);
            aegLevel[v] = (short) aeg;
            envelopeFraction[v] = 0;
            int e = (int) (((long) volume[v] * (short) aeg) >> 15);
            e = (int) (((long) e * (short) master) >> 15);
            envelopeStep[v] = (short) (e - envelope[v]);

            if (panStepL[v] != 0) {
                panL[v] += panStepL[v];
                if ((short) (panTargetL[v] - panL[v]) * panStepL[v] < 0) {
                    panL[v] = panTargetL[v];
                    panStepL[v] = 0;
                }
            }
            if (panStepR[v] != 0) {
                panR[v] += panStepR[v];
                if ((short) (panTargetR[v] - panR[v]) * panStepR[v] < 0) {
                    panR[v] = panTargetR[v];
                    panStepR[v] = 0;
                }
            }
            gainL[v] = (short) ((panL[v] * (long) (short) ampMod) >> 15);
            gainR[v] = (short) ((panR[v] * (long) (short) ampMod) >> 15);

            if (lfoDelay[v] >= 0) {
                if (lfoDelay[v] == 0) lfoRate[v] = lfoRateNext[v];
                lfoDelay[v]--;
            }
            if (aegCount[v] >= 0) {
                if (aegCount[v] == 0) {
                    aegRate[v] = aegRate2[v];
                    aegTarget[v] = aegTarget2[v];
                    flags[v] = (short) (flag & ~2);
                }
                aegCount[v]--;
            }
            if (fegCount[v] >= 0) {
                if (fegCount[v] == 0) {
                    fegTarget[v] = fegTarget2[v];
                    fegRate[v] = fegRate2[v];
                }
                fegCount[v]--;
            }
            if (pegCount[v] >= 0) {
                if (pegCount[v] == 0) {
                    pegTarget[v] = pegTarget2[v];
                    pegRate[v] = pegRate2[v];
                }
                pegCount[v]--;
            }
        }
    }

    /** a sample of all voices into {@link #mix} at the offset (0x10003a30) */
    private void tick(int offset) {
        for (int v = 0; v < VOICES; v++) {
            if (flags[v] == 0) continue;
            sample[v] = (short) oscillate(v);
            int x = ((envelope[v] & 0xffff) << 7) | ((envelopeFraction[v] & 0xffff) >> 9);
            x += envelopeStep[v];
            envelope[v] = (short) (x >> 7);
            envelopeFraction[v] = (short) (x << 9);
        }
        long l = 0, r = 0;
        for (int v = VOICES - 1; v >= 0; v--) {
            if (flags[v] == 0) continue;
            int amp = (int) (((long) (envelope[v] & 0xffff) * sample[v]) >> 12);
            l += (amp * gainL[v]) >> 1;
            r += (amp * gainR[v]) >> 1;
        }
        l >>= 5;
        r >>= 5;
        if (shift >= 0) {
            l <<= shift;
            r <<= shift;
        } else {
            l >>= -shift;
            r >>= -shift;
        }
        mix[offset] = (short) l;
        mix[offset + 1] = (short) r;
    }

    /** a sample of a voice, filtered (0x100038d0) */
    private int oscillate(int v) {
        int p = position[v];
        int at = (p >> 16) + address[v] * 4;
        int fraction = (p >> 7) & 0x1ff;
        int next = increment[v] + p;
        int over = next - end[v];
        position[v] = over >= 0 ? loopStart[v] + over : next;

        long b = band[v];
        low[v] += (short) ((b * cutoff[v]) >> 14);
        int s0 = pcm(at), s1 = pcm(at + 1);
        int in = (((s1 - s0) * fraction) >> 7) + s0 * 4;
        int damping = (int) ((resonance[v] * b) >> 14) & 0xffff;
        int high = (short) (in - damping - (low[v] & 0xffff));
        band[v] += (short) ((high * (long) cutoff[v]) >> 14);
        return switch (filterType[v]) {
            case 1 -> low[v];
            case 2 -> high;
            case 3 -> band[v];
            default -> (short) in;
        };
    }

    /** the dll reads on past the wave memory, here it is silence */
    private int pcm(int at) {
        at += RohmRom.WAVE_DATA;
        return at >= 0 && at < memory.length ? memory[at] : 0;
    }

    /** the commands posted before this block (0x10003dd0) */
    private void execute() {
        List<short[]> queue = commands[toggle];
        for (short[] c : queue) {
            int v = c[1];
            switch (c[0]) {
            case OFF -> flags[v] = 0;
            case RELEASE -> {
                flags[v] &= ~2;
                aegRate[v] = c[2];
                aegTarget[v] = 0;
                aegCount[v] = -1;
                fegCount[v] = -1;
                pegCount[v] = -1;
                fegRate[v] = c[3];
                fegTarget[v] = c[4];
                pegRate[v] = c[5];
                pegTarget[v] = c[6];
            }
            case KEY_ON -> keyOn(v, c);
            case CUT -> {
                flags[v] &= ~2;
                aegRate[v] = 0x7fff;
                aegTarget[v] = 0;
                aegCount[v] = -1;
            }
            case VOLUME -> {
                volume[v] = c[2];
                panTargetL[v] = c[3];
                panTargetR[v] = c[4];
                panStepL[v] = (short) panStep(c[3] - panL[v]);
                panStepR[v] = (short) panStep(c[4] - panR[v]);
            }
            case PITCH -> pitchBase[v] = (c[2] & 0xffff) | (c[3] << 16);
            case MODULATION -> {
                lfoPitchUp[v] = c[2];
                lfoPitchDown[v] = c[3];
                if (lfoDelay[v] > 0) lfoDelay[v] = 0;
            }
            default -> {}
            }
        }
        queue.clear();
    }

    private int panStep(int distance) {
        return distance == 0 ? 0 : (distance >= 0 ? 0x8000 : -0x8000) / (1 << panShift);
    }

    private void keyOn(int v, short[] c) {
        address[v] = c[2] & 0xffff;
        loopStart[v] = c[3] << 16;
        position[v] = 0;
        end[v] = c[4] << 16;
        pitchBase[v] = (c[6] & 0xffff) | (c[7] << 16);
        flags[v] = 3;
        aegLevel[v] = c[8];
        aegRate[v] = c[9];
        aegRate2[v] = c[10];
        aegCount[v] = c[11];
        aegTarget[v] = 0x7fff;
        aegTarget2[v] = c[12];
        volume[v] = c[13];
        panL[v] = panTargetL[v] = c[14];
        panR[v] = panTargetR[v] = c[15];
        panStepL[v] = panStepR[v] = 0;
        envelope[v] = (short) ((((c[13] * aegLevel[v]) >> 15) * (short) master) >> 15);
        fegLevel[v] = c[16];
        fegRate[v] = c[17];
        fegRate2[v] = c[18];
        fegCount[v] = c[19];
        fegTarget[v] = c[20];
        fegTarget2[v] = c[21];
        pegLevel[v] = c[22];
        pegRate[v] = c[23];
        pegRate2[v] = c[24];
        pegCount[v] = c[25];
        pegTarget[v] = c[26];
        pegTarget2[v] = c[27];
        filterType[v] = c[40];
        cutoffBase[v] = c[28];
        resonance[v] = c[31];
        cutoffMin[v] = c[29];
        cutoffMax[v] = c[30];
        band[v] = 0;
        low[v] = 0;
        lfoPhase[v] = 0x4000;
        lfoRate[v] = 0;
        lfoRateNext[v] = c[32];
        lfoDelay[v] = c[33];
        lfoAmpUp[v] = c[36];
        lfoAmpDown[v] = c[37];
        lfoPitchUp[v] = c[34];
        lfoPitchDown[v] = c[35];
        lfoFilterUp[v] = c[38];
        lfoFilterDown[v] = c[39];
    }

    /** the level of the voices for the driver, a voice run out stops (0x10003c20) */
    private void updateStatus() {
        for (int v = 0; v < VOICES; v++) {
            int s = 0;
            if ((flags[v] & 1) != 0) {
                s = (aegLevel[v] >> 5) & 0xffff;
                if ((flags[v] & 2) != 0) s |= 0x800;
                if (s == 0) flags[v] = 0;
            }
            status[v] = s;
        }
    }
}
