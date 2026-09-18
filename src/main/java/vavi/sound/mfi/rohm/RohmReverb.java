/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import java.util.Arrays;


/**
 * The reverb of {@code rt_synth_2.dll} after the voices.
 * <p>
 * Eight feedback delays, four for a side, which run on every other sample, one side on one and
 * the other on the next, and a windowed sinc to fill the samples between. What they give back
 * is mixed with what goes in by the dry and the wet levels of a preset ({@link RohmRom.Reverb}).
 * <p>
 * It is off until a preset is chosen, which is by the 12th entry of the dll's synthesizer and
 * by a gm system on (preset 0, which is dry).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class RohmReverb {

    private static final int RATE = 44100;

    private final RohmRom rom;

    /** -1: off */
    private int preset = -1;
    private final short[] buffer = new short[0x960 * 2];
    private final short[] gain = new short[8];
    private final int[] start = new int[8], end = new int[8], index = new int[8];
    private int decimation = 1;
    private int phase;
    /** Q14 */
    private int dry, wet;
    /** what the delays gave back, the last 4 of a side */
    private final int[] historyL = new int[4], historyR = new int[4];
    /** the interpolation, Q15 */
    private final short[] sinc = new short[8];

    RohmReverb(RohmRom rom) {
        this.rom = rom;
        for (int n = 0; n < 8; n++) {
            double x = n * Math.PI / 4;
            double s = n != 0 ? Math.sin(x) / x : 1;
            double window = 0.42 - 0.5 * Math.cos(Math.PI / 8 * (n + 8)) + 0.08 * Math.cos(Math.PI / 4 * (n + 8));
            sinc[n] = (short) (int) (s * window * 32768);
        }
        clear();
    }

    /** 0x10002e70 */
    private void clear() {
        Arrays.fill(historyL, 0);
        Arrays.fill(historyR, 0);
        Arrays.fill(buffer, (short) 0);
        preset = -1;
        wet = 0;
        Arrays.fill(gain, (short) 0);
        decimation = 0;
        phase = 0;
        dry = 100;
        Arrays.fill(end, 0);
    }

    /** @param preset 0 ~ 7, -1: off (0x10002fe0) */
    void select(int preset) {
        clear();
        if (preset >= 0) {
            RohmRom.Reverb r = rom.reverbs[preset];
            decimation = r.decimation == 0 ? 1 : 2 << (r.decimation - 1);
            int length = 0;
            for (int i = 0; i < 8; i++) {
                int d = r.delays[i];
                length += d / decimation + (d % decimation != 0 ? 1 : 0);
                end[i] += length;
                start[i] = i > 0 ? end[i - 1] : 0;
                index[i] = start[i];
                gain[i] = r.time > 0 ? (short) (int) (Math.pow(10, -3.0 * d / (RATE * r.time)) * 32768) : 0;
            }
            wet = (r.wet << 14) / 100;
            dry = (r.dry << 14) / 100;
        }
        this.preset = preset;
    }

    /** a delay: what it gives back, and what goes in with the feedback (0x10003130) */
    private int delay(int in, int i) {
        int at = index[i];
        int next = at + 1;
        if (next >= end[i]) {
            next = i > 0 ? start[i] : 0;
        }
        index[i] = next;
        int out = buffer[next];
        int feedback = (gain[i] * out) >> 15;
        if (feedback < 0) feedback++;
        buffer[at] = (short) Math.clamp(feedback + in, -0x8000, 0x7fff);
        return out;
    }

    /** a frame, in place (0x100031c0) */
    void process(short[] frame, int offset) {
        if (preset == -1) return;
        int l = frame[offset], r = frame[offset + 1];
        long wetL, wetR;
        int k = 4 / decimation;
        if (phase == 0) {
            long w = ((long) delay(l, 0) + delay(r, 2) + delay(l, 4) + delay(r, 6)) >> 2;
            wetL = w;
            if (decimation > 1) {
                push(historyL, (int) w);
                wetL = historyL[1];
            }
            wetR = ((long) (sinc[k] * historyR[1]) + (long) (sinc[4 + k] * historyR[0])
                    + (long) (sinc[8 - k] * historyR[3]) + (long) (sinc[4 - k] * historyR[2])) >> 15;
        } else {
            long w = ((long) delay(r, 1) + delay(l, 3) + delay(r, 5) + delay(l, 7)) >> 2;
            wetR = w;
            if (decimation > 1) {
                push(historyR, (int) w);
                wetR = historyR[1];
            }
            k *= phase;
            wetL = ((long) (sinc[4 + k] * historyL[0]) + (long) (sinc[k] * historyL[1])
                    + (long) (sinc[8 - k] * historyL[3]) + (long) (sinc[4 - k] * historyL[2])) >> 15;
        }
        if (++phase >= decimation) phase = 0;
        frame[offset] = (short) Math.clamp(((long) (l * dry) + wet * wetL) >> 14, -0x8000, 0x7fff);
        frame[offset + 1] = (short) Math.clamp(((long) (r * dry) + wet * wetR) >> 14, -0x8000, 0x7fff);
    }

    private static void push(int[] history, int value) {
        history[0] = history[1];
        history[1] = history[2];
        history[2] = history[3];
        history[3] = value;
    }
}
