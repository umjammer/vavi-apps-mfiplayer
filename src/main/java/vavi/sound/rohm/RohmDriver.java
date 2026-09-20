/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.rohm;

import java.util.Arrays;


/**
 * The driver half of {@code rt_synth_2.dll}: notes into the voices of {@link RohmLsi}.
 * <p>
 * A note is a program and a key. The program has up to two layers, a layer is a list of
 * zones split by key, so a note sounds one voice a layer. The zone is turned into the registers
 * of a voice through the tables of the rom, and those go to the chip as a key on command.
 * <p>
 * 64 notes, which hold the voices they sound. A note gets its voices from the free ones, or
 * takes them from the note sounding the least (the oldest of those as much), whose voices are
 * cut at the start of the next block.
 * <p>
 * The program of a channel is an index of the program table: 0 ~ 127 the melody group,
 * 0x80 a drum channel, whose key is the program (0x80 + key), 0xde ~ 0xe3 the group 0x7d,
 * 0xe4 ~ 0xeb the group 0x11, 0xff nothing.
 * <p>
 * The arithmetic and the quirks are the dll's, a volume change moves the pans a step to the left,
 * a pan change takes the velocity as it is rather than as a level, a pitch bend of a drum takes
 * its key rather than the key the drum is played at.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class RohmDriver {

    static final int NOTES = 64;
    static final int CHANNELS = 16;
    /** no voice */
    private static final int NONE = 0x40;
    /** the program of a drum channel */
    static final int DRUM = 0x80;

    private final RohmRom rom;
    private final RohmLsi lsi;
    /** {@link RohmRom#memory} of the sound source */
    private final byte[] memory;

    // a channel (10 bytes of the dll)

    /** the program index */
    final int[] program = new int[CHANNELS];
    /** 7 bit */
    final int[] volume = new int[CHANNELS];
    /** signed 8 bit */
    final int[] modulation = new int[CHANNELS];
    /** signed 16 bit, 1/64 semitones */
    final int[] bend = new int[CHANNELS];
    /** 0 ~ 127 */
    final int[] pan = new int[CHANNELS];
    final boolean[] hold = new boolean[CHANNELS];

    // a note (0x24 bytes of the dll)

    private final int[] next = new int[NOTES + 1], previous = new int[NOTES + 1];
    /** priority(3) | 0x1000: just struck | level(12) */
    private final int[] level = new int[NOTES];
    private final int[][] voice = new int[NOTES][2];
    /** zone indices, -1: none */
    private final int[][] zone = new int[NOTES][2];
    /** channel << 7 | key */
    private final int[] key = new int[NOTES];
    private final int[] velocity = new int[NOTES];
    /** signed, the pan of the program */
    private final int[] programPan = new int[NOTES];
    /** exclusive group | 8: cut */
    private final int[] group = new int[NOTES];
    private final int[] noteProgram = new int[NOTES];
    /** a note off while the hold pedal is down */
    private final boolean[] held = new boolean[NOTES];

    /** the head and the tail of the notes sounding */
    private static final int ACTIVE = NOTES;
    /** the notes free, linked by {@link #next} */
    private int free;

    /** the voices free, a stack */
    private final int[] stack = new int[RohmLsi.VOICES];
    /** the top of {@link #stack}, -1: empty */
    private int top;

    /** master coarse tuning [semitones] */
    int transpose;

    RohmDriver(RohmRom rom, byte[] memory, RohmLsi lsi) {
        this.rom = rom;
        this.memory = memory;
        this.lsi = lsi;
        for (int[] z : zone) Arrays.fill(z, -1);
        for (int[] v : voice) Arrays.fill(v, NONE);
    }

    /** 0x100046d0, the programs stay */
    void reset() {
        for (int c = 0; c < CHANNELS; c++) {
            volume[c] = 0x7f;
            hold[c] = false;
            pan[c] = 0x40;
            modulation[c] = 0;
            bend[c] = 0;
        }
        Arrays.fill(lsi.status, 0);
        for (int i = 0; i < stack.length; i++) stack[i] = i;
        top = RohmLsi.VOICES - 1;
        next[ACTIVE] = previous[ACTIVE] = ACTIVE;
        for (int n = 0; n < NOTES; n++) {
            level[n] &= 0xe000;
            held[n] = false;
            next[n] = n + 1 < NOTES ? n + 1 : -1;
            voice[n][0] = voice[n][1] = NONE;
        }
        free = 0;
        lsi.clearQueues();
    }

    /** 0x10004520 */
    void resetChannel(int channel) {
        volume[channel] = 0x7f;
        modulation[channel] = 0;
        pan[channel] = 0x40;
        bend[channel] = 0;
        program[channel] = 0;
        hold(channel, false);
    }

    /** 0x10004470 */
    void program(int channel, int program) {
        this.program[channel] = program & 0xff;
    }

    /** 0x10004560 */
    void transpose(int transpose) {
        this.transpose = transpose;
    }

    private boolean sounding(int n) {
        return (level[n] & 0x1fff) != 0;
    }

    private boolean of(int n, int channel) {
        return (key[n] & 0x7f80) == channel << 7;
    }

    // ---- notes

    /** 0x100042f0 */
    void noteOn(int channel, int k, int velocity) {
        int program = this.program[channel];
        int key;
        if (program == DRUM) {
            program = k + DRUM;
            key = k + (k >= 0x5e ? 0x15 : 0);
        } else {
            key = k;
        }
        noteOn(channel, program, key, velocity);
    }

    /** 0x100055b0 */
    private void noteOn(int channel, int program, int k, int velocity) {
        RohmRom.Program p = rom.program(program);
        if (p == null) return;
        int key = k;
        if (program >= DRUM && program < 0xde) {
            key = p.key;
        }
        // the exclusive group cuts the notes of it
        if (p.exclusiveGroup != 0) {
            for (int n = next[ACTIVE]; n != ACTIVE; n = next[n]) {
                if ((group[n] & 0xf) != p.exclusiveGroup) continue;
                for (int i = 0; i < 2; i++) {
                    if (voice[n][i] != NONE) {
                        group[n] |= 8;
                        lsi.post(new short[] { RohmLsi.CUT, (short) voice[n][i] });
                    }
                }
            }
        }
        int[] zones = zones(transpose + key, p);
        if (zones.length == 0) return;
        int[] voices = allocate(zones.length);
        if (voices == null) return;

        int n = free;
        free = next[n];
        voice[n][0] = voices[0];
        voice[n][1] = voices[1];
        zone[n][0] = zones[0];
        zone[n][1] = zones.length > 1 ? zones[1] : -1;
        noteProgram[n] = program;
        held[n] = false;
        previous[n] = ACTIVE;
        next[n] = next[ACTIVE];
        previous[next[ACTIVE]] = n;
        next[ACTIVE] = n;
        level[n] = 0x1fff;
        this.key[n] = ((channel << 7) | k) & 0xffff;
        this.velocity[n] = velocity;
        programPan[n] = p.pan;
        int pan = Math.clamp(p.pan + this.pan[channel], 0, 0x7f);
        group[n] = p.exclusiveGroup;

        for (int i = 0; i < zones.length; i++) {
            int z = RohmRom.ZONE_TABLE + zones[i] * 0x20;
            byte[] r = memory;
            short[] c = new short[RohmLsi.KEY_ON_LENGTH];
            c[0] = RohmLsi.KEY_ON;
            c[1] = (short) voices[i];
            int w = RohmRom.WAVE_TABLE + (r[z + 1] & 0xff) * 12;
            c[2] = (short) RohmRom.u16(memory, w);
            c[3] = (short) RohmRom.u16(memory, w + 2);
            c[4] = (short) RohmRom.u16(memory, w + 4);

            // the pitch, the key is followed, followed a quarter or not
            int fine = r[z + 3] + bend[channel];
            int tracked = key;
            int follow = (r[z + 0x1c] & 0xff) >> 6;
            if (follow == 2) {
                tracked = 0x40;
            } else if (follow == 1) {
                int d = key - r[z + 4] - 0x40;
                if (d >= 0) {
                    tracked = (d >> 2) + 0x40;
                    fine += (d & 3) << 4;
                } else {
                    tracked = 0x40 - (-d >> 2);
                    fine += -(-d & 3) << 4;
                }
            }
            int increment = pitch(((r[z + 4] + tracked + transpose) << 6) + fine, waveRate(w));
            c[6] = (short) increment;
            c[7] = (short) (increment >> 16);

            // the filter, the cutoff follows the key from a point
            int f = r[z + 0x1f] & 0xff;
            int curve = f >> 4;
            int keyFollow = (f >> 2) & 3;
            int cut;
            if (keyFollow != 0) {
                cut = (r[z + 0x11] & 0xff) - (((short) tracked - (r[z + 2] & 0xff)) >> (keyFollow - 1));
            } else {
                cut = (r[z + 0x11] & 0xff) + (tracked - (r[z + 2] & 0xff));
            }
            cut = Math.clamp((short) cut, 0x10, 0x70);
            int index = curve * 97 + cut;
            c[28] = rom.cutoff(index);
            c[29] = rom.cutoff(curve * 97 + 0x10);
            c[30] = rom.cutoff(curve * 97 + 0x70);
            c[31] = rom.resonance(index);
            c[40] = (short) (f & 3);

            // the amplitude
            c[8] = rom.level((r[z + 0x1c] & 0x3f) * 2);
            c[9] = rom.rate(r[z + 0x19] & 0xff);
            c[10] = rom.rate(r[z + 0x1a] & 0xff);
            c[11] = rom.time(r[z + 0x19] & 0xff);
            c[12] = rom.level(r[z + 0x1d] & 0xff);
            c[13] = (short) (((short) ((rom.level(volume[channel] & 0x7f) * rom.level(r[z + 0x1e] & 0xff)) >> 15)
                    * rom.level(velocity & 0x7f)) >> 15);
            c[14] = rom.pan(128 - pan);
            c[15] = rom.pan(pan);

            // the filter envelope
            c[16] = rom.egLevel(r[z + 0x15] & 0xff);
            c[17] = rom.rate(r[z + 0x12] & 0xff);
            c[18] = rom.rate(r[z + 0x13] & 0xff);
            c[19] = rom.time(r[z + 0x12] & 0xff);
            c[20] = rom.egLevel(r[z + 0x18] & 0xff);
            c[21] = rom.egLevel(r[z + 0x16] & 0xff);

            // the pitch envelope
            c[22] = rom.egLevel(r[z + 0x0d] & 0xff);
            c[23] = rom.rate(r[z + 0x0a] & 0xff);
            c[24] = rom.rate(r[z + 0x0b] & 0xff);
            c[25] = rom.time(r[z + 0x0a] & 0xff);
            c[26] = rom.egLevel(r[z + 0x10] & 0xff);
            c[27] = rom.egLevel(r[z + 0x0e] & 0xff);

            // the lfo
            c[32] = rom.lfoRate(r[z + 5] & 0xff);
            c[33] = rom.time(r[z + 9] & 0xff);
            int depth = Math.min((r[z + 6] & 0xff) + modulation[channel], 0x7f);
            c[34] = rom.lfoPitchUp(depth);
            c[35] = rom.lfoPitchDown(depth);
            c[36] = rom.lfoDepthUp(r[z + 8] & 0xff);
            c[37] = rom.lfoDepthDown(r[z + 8] & 0xff);
            c[38] = rom.lfoDepthUp(r[z + 7] & 0xff);
            c[39] = rom.lfoDepthDown(r[z + 7] & 0xff);

            lsi.post(c);
        }
    }

    /** the zones of the layers of a program for a key, the walk goes on past the zones as the dll's does (0x10005500) */
    private int[] zones(int key, RohmRom.Program p) {
        if (key < p.key || p.layers[0] < 0) return new int[0];
        int[] result = new int[2];
        int count = 0;
        for (int layer : p.layers) {
            if (layer < 0) break;
            for (int z = layer; RohmRom.ZONE_TABLE + z * 0x20 < memory.length; z++) {
                int high = memory[RohmRom.ZONE_TABLE + z * 0x20];
                if (key <= (high & 0x7f)) {
                    result[count++] = z;
                    break;
                }
                if (high < 0) break;
            }
        }
        return Arrays.copyOf(result, count);
    }

    /** the pitch of a wave for a key in 1/64 semitones, 16.16 (0x10005d70) */
    private int pitch(int key64, int rate) {
        int n = 0x81 - (key64 >> 6);
        int octave = n / 12;
        int top = octave * 12;
        if (top < n) {
            top += 12;
            octave++;
        }
        int index = (top - 0x81) * 0x40 + key64;
        long x = (long) rom.pitch(index) * rate;
        int shift = (octave + 0x12) & 0xff;
        return shift < 0x40 ? (int) (x >> shift) : (int) (x >> 63);
    }

    private int waveRate(int w) {
        return RohmRom.u16(memory, w + 8) | (RohmRom.u16(memory, w + 10) << 16);
    }

    /**
     * the voices free, or those of the note sounding the least (0x10006020)
     * @return two voices, the second may be {@link #NONE}, null: none
     */
    private int[] allocate(int count) {
        int[] result = { NONE, NONE };
        int i = 0;
        while (top >= 0) {
            result[i++] = stack[top--];
            if (--count <= 0) return result;
        }
        while (count > 0) {
            int victim = -1;
            int least = 0x8000;
            for (int n = previous[ACTIVE]; n != ACTIVE; n = previous[n]) {
                if ((level[n] >> 13) <= 0 && (level[n] & 0x1fff) < least) {
                    least = level[n] & 0x1fff;
                    victim = n;
                }
            }
            if (victim < 0) return null;
            unlink(victim);
            level[victim] &= 0xe000;
            for (int j = 0; j < 2; j++) {
                int v = voice[victim][j];
                if (v == NONE) continue;
                lsi.postCut(v);
                if (count > 0) {
                    result[i++] = v;
                    count--;
                } else {
                    stack[++top] = v;
                }
            }
        }
        return result;
    }

    private void unlink(int n) {
        next[previous[n]] = next[n];
        previous[next[n]] = previous[n];
        next[n] = free;
        free = n;
    }

    /** 0x100043c0 */
    void noteOff(int channel, int k) {
        int key = program[channel] == DRUM ? (k + (k >= 0x5e ? 0x15 : 0)) & 0x7f : k & 0x7f;
        int match = (channel << 7) | key;
        for (int n = 0; n < NOTES; n++) {
            if (!sounding(n) || this.key[n] != match) continue;
            if (hold[channel]) {
                held[n] = true;
            } else {
                release(n);
            }
        }
    }

    /** 0x10005c40, the drums but the long whistle and the long guiro go on */
    private void release(int n) {
        if (!sounding(n) || (group[n] & 8) != 0) return;
        int p = noteProgram[n];
        if (p >= 0x80 && p <= 0xae && p != 0xa4 && p != 0xa5) return;
        for (int i = 0; i < 2; i++) {
            int z = zone[n][i];
            if (voice[n][i] == NONE || z < 0) continue;
            byte[] r = memory;
            z = RohmRom.ZONE_TABLE + z * 0x20;
            lsi.post(new short[] { RohmLsi.RELEASE, (short) voice[n][i],
                    rom.rate(r[z + 0x1b] & 0xff), rom.rate(r[z + 0x14] & 0xff), rom.egLevel(r[z + 0x17] & 0xff),
                    rom.rate(r[z + 0x0c] & 0xff), rom.egLevel(r[z + 0x0f] & 0xff) });
        }
    }

    /** 0x10004490 */
    void hold(int channel, boolean on) {
        hold[channel] = on;
        if (on) return;
        for (int n = 0; n < NOTES; n++) {
            if (sounding(n) && of(n, channel) && held[n]) {
                release(n);
            }
        }
    }

    /** all notes off and all sound off, cut (0x100052c0) */
    void cut(int channel) {
        for (int n = 0; n < NOTES; n++) {
            if (!sounding(n) || !of(n, channel)) continue;
            for (int i = 0; i < 2; i++) {
                if (voice[n][i] != NONE) {
                    group[n] |= 8;
                    lsi.post(new short[] { RohmLsi.CUT, (short) voice[n][i] });
                }
            }
        }
    }

    // ---- controllers

    /** 0x100050a0 */
    void volume(int channel, int value) {
        volume[channel] = value & 0x7f;
        for (int n = 0; n < NOTES; n++) {
            if (!sounding(n) || !of(n, channel)) continue;
            int pan = Math.clamp(programPan[n] + this.pan[channel], 0, 0x7f);
            for (int i = 0; i < 2; i++) {
                int z = zone[n][i];
                if (voice[n][i] == NONE || z < 0) continue;
                int v = (((short) ((rom.level(velocity[n] & 0x7f) * rom.level(memory[RohmRom.ZONE_TABLE + z * 0x20 + 0x1e] & 0xff)) >> 15))
                        * rom.level(volume[channel])) >> 15;
                lsi.post(new short[] { RohmLsi.VOLUME, (short) voice[n][i], (short) v, rom.pan(127 - pan), rom.pan(pan) });
            }
        }
    }

    /** 0x10005140 */
    void pan(int channel, int value) {
        pan[channel] = value;
        for (int n = 0; n < NOTES; n++) {
            if (!sounding(n) || !of(n, channel)) continue;
            int pan = Math.clamp(programPan[n] + value, 0, 0x7f);
            for (int i = 0; i < 2; i++) {
                int z = zone[n][i];
                if (voice[n][i] == NONE || z < 0) continue;
                int v = ((((velocity[n] << 8) * rom.level(memory[RohmRom.ZONE_TABLE + z * 0x20 + 0x1e] & 0xff)) >> 15) & 0xffff)
                        * rom.level(volume[channel]) >> 15;
                lsi.post(new short[] { RohmLsi.VOLUME, (short) voice[n][i], (short) v, rom.pan(128 - pan), rom.pan(pan) });
            }
        }
    }

    /** @param value signed, 1/64 semitones (0x100051c0) */
    void bend(int channel, int value) {
        bend[channel] = (short) value;
        for (int n = 0; n < NOTES; n++) {
            if (!sounding(n) || !of(n, channel)) continue;
            int key = this.key[n] & 0x7f;
            for (int i = 0; i < 2; i++) {
                int z = zone[n][i];
                if (voice[n][i] == NONE || z < 0) continue;
                byte[] r = memory;
                z = RohmRom.ZONE_TABLE + z * 0x20;
                int fine = r[z + 3] + bend[channel];
                int tracked = key;
                int follow = (r[z + 0x1c] & 0xff) >> 6;
                if (follow == 2) {
                    tracked = 0x40;
                } else if (follow == 1) {
                    // unlike a key on, the coarse tune is in the key followed, and it is added again
                    int coarse = r[z + 4] & 0xff;
                    int d = key - coarse - 0x40;
                    if (d >= 0) {
                        tracked = (d >> 2) + coarse + 0x40;
                        fine += (d & 3) << 4;
                    } else {
                        tracked = coarse - (-d >> 2) + 0x40;
                        fine += -(-d & 3) << 4;
                    }
                }
                int increment = pitch(((r[z + 4] + tracked + transpose) << 6) + fine, waveRate(RohmRom.WAVE_TABLE + (r[z + 1] & 0xff) * 12));
                lsi.post(new short[] { RohmLsi.PITCH, (short) voice[n][i], (short) increment, (short) (increment >> 16) });
            }
        }
    }

    /** 0x10005240 */
    void modulation(int channel, int value) {
        modulation[channel] = (byte) value;
        for (int n = 0; n < NOTES; n++) {
            if (!sounding(n) || !of(n, channel)) continue;
            for (int i = 0; i < 2; i++) {
                int z = zone[n][i];
                if (voice[n][i] == NONE || z < 0) continue;
                int depth = Math.min((memory[RohmRom.ZONE_TABLE + z * 0x20 + 6] & 0xff) + value, 0x7f);
                lsi.post(new short[] { RohmLsi.MODULATION, (short) voice[n][i], rom.lfoPitchUp(depth), rom.lfoPitchDown(depth) });
            }
        }
    }

    // ---- after a block

    /** the notes whose voices have run out are freed (0x10004570) */
    void update(int[] status) {
        for (int n = next[ACTIVE]; n != ACTIVE; ) {
            int following = next[n];
            if ((level[n] & 0x1000) == 0) {
                level[n] &= 0xe000;
                for (int i = 0; i < 2; i++) {
                    int v = voice[n][i];
                    if (v == NONE) continue;
                    int s = status[v];
                    if (s == 0) {
                        stack[++top] = v;
                        voice[n][i] = NONE;
                    } else if ((level[n] & 0x1fff) < s) {
                        level[n] = (level[n] & ~0x1fff) | (s & 0x1fff);
                    }
                }
                if ((level[n] & 0x1fff) == 0) {
                    unlink(n);
                }
            } else {
                level[n] = (level[n] & 0xefff) | 0xfff;
            }
            n = following;
        }
    }
}
