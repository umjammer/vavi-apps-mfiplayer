/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.rohm;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import vavi.sound.faith.FaithRom;

import static java.lang.System.getLogger;


/**
 * The rom of the rohm sound source, read out of the installed {@code rt_synth_2.dll}
 * (faith "Ring Tone Authoring Tool", the "Ring Tone LSI Simulator Type 2"), nothing of it
 * is distributed with this.
 * <p>
 * The dll is a 32 bit PE whose {@code .data} holds the whole of the rom, at the addresses
 * of the one build known ({@link #TIMESTAMP}):
 * <pre>
 * wave         0x100172d0  256 * 12  address / 4(2) loopStart(2) end(2) pad(2) pitch(4)
 * zone         0x10017ed0  640 * 32  keyHigh | 0x80 last(1) wave(1) ... the voice parameters, see {@link RohmDriver}
 * program      0x1001ced0  256 * 6   key(1) pan(1) layer(2) layer(2), a layer is exclusive group(4) | zone(12), 0xfff: none
 * wave memory  0x1001d4d0  0x22000   signed 8 bit pcm, the last 0x2000 are for the UCS waves
 * </pre>
 * one after another, and the tables the driver turns a zone into the registers of a voice with,
 * all of which are kept as they are in the dll ({@link #memory}): the dll walks the zones of a
 * program until one takes the key, and a program of UCS with no UCS loaded walks on into the
 * program table and plays what it finds there.
 * <p>
 * The program table is the one thing the dll changes when it starts: it clears the pan of
 * every program and writes the drum pans and the exclusive groups of the drum programs, which
 * is done here as well.
 * <p>
 * system property
 * <li>{@code vavi.sound.faith.path} ... the authoring tool's {@code Tools} directory, see {@link FaithRom#PATH_KEY}</li>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public final class RohmRom {

    private static final Logger logger = getLogger(RohmRom.class.getName());

    /** the dll */
    public static final String DLL = "rt_synth_2.dll";

    /** the PE time stamp of the {@code rt_synth_2.dll} the addresses are of (2003-08-28) */
    static final int TIMESTAMP = 0x3f4d683f;

    // the addresses in the dll

    static final int PROGRAMS = 0x1001ced0;
    static final int ZONES = 0x10017ed0;
    static final int WAVES = 0x100172d0;
    static final int WAVE_MEMORY = 0x1001d4d0;
    static final int DRUM_PAN = 0x1003f4d0;
    static final int DRUM_PAN_2 = 0x1003f500;
    static final int EXCLUSIVE = 0x1003f510;
    static final int EXCLUSIVE_2 = 0x1003f570;
    static final int LEVEL = 0x1003f5d0;
    static final int PAN = 0x1003f6d0;
    static final int LFO_PITCH_UP = 0x1003f7d8;
    static final int LFO_PITCH_DOWN = 0x1003f8d8;
    static final int LFO_DEPTH_UP = 0x1003f9d8;
    static final int LFO_DEPTH_DOWN = 0x1003fad8;
    static final int LFO_RATE = 0x1003fbd8;
    static final int EG_LEVEL = 0x1003fcd8;
    static final int TIME = 0x1003fee0;
    static final int RATE = 0x1003ffe0;
    static final int CUTOFF = 0x100401c0;
    static final int RESONANCE = 0x10040de0;
    static final int PITCH = 0x10041a20;
    static final int REVERB = 0x100170e0;

    public static final int PROGRAM_COUNT = 256;
    /** the size of the wave memory, rom and ram */
    public static final int WAVE_MEMORY_SIZE = 0x22000;
    /** where the ram for the UCS waves starts in the wave memory */
    public static final int WAVE_RAM = 0x20000;
    /** the zones of the UCS voices, 8 of them */
    public static final int UCS_ZONE = 576;
    /** the waves of the UCS voices, 10 of them */
    public static final int UCS_WAVE = 192;
    /** the reverb presets */
    public static final int REVERB_COUNT = 8;

    /** a program: which zones sound for a key */
    public static final class Program {
        /** a drum: the key it is played at, a melody: the lowest key it sounds */
        public final int key;
        /** signed, added to the pan of the channel */
        public final int pan;
        /** 0: none, the notes of a group stop each other (hi-hats) */
        public final int exclusiveGroup;
        /** zone indices of the two layers, -1: none */
        public final int[] layers;

        Program(int key, int pan, int exclusiveGroup, int[] layers) {
            this.key = key;
            this.pan = pan;
            this.exclusiveGroup = exclusiveGroup;
            this.layers = layers;
        }
    }

    /** a reverb preset */
    public static final class Reverb {
        /** reverb time [s], 0: none */
        public final double time;
        /** the delays of the 8 comb filters [samples at 44.1 kHz] */
        public final int[] delays;
        /** the combs run at the rate divided by 2^n, 0: 1 */
        public final int decimation;
        /** [%] */
        public final int dry, wet;

        Reverb(double time, int[] delays, int decimation, int dry, int wet) {
            this.time = time;
            this.delays = delays;
            this.decimation = decimation;
            this.dry = dry;
            this.wet = wet;
        }
    }

    /** where the .data of the dll is */
    static final int DATA = 0x10017000;

    // offsets in the memory

    static final int WAVE_TABLE = WAVES - DATA;
    static final int ZONE_TABLE = ZONES - DATA;
    static final int PROGRAM_TABLE = PROGRAMS - DATA;
    static final int WAVE_DATA = WAVE_MEMORY - DATA;

    /**
     * the .data of the dll, the programs patched as the dll patches them, what a sound source
     * copies and writes the UCS into. The dll indexes its tables by the bytes of a zone and may
     * read on into what is next to them, which it does here as well.
     */
    final byte[] memory;

    /** @return a word of the rom, 0 out of the data */
    private short word(int va, int i) {
        return word(memory, va - DATA + i * 2);
    }

    /** @return a little endian word, 0 out of the memory */
    static short word(byte[] memory, int offset) {
        return offset >= 0 && offset + 1 < memory.length ? (short) u16(memory, offset) : 0;
    }

    /** @return 15 bit, the level of a 7 bit value */
    short level(int i) { return word(LEVEL, i); }
    /** @return 15 bit, a pan of 0 ~ 127 is [128 - pan] left and [pan] right */
    short pan(int i) { return word(PAN, i); }
    short lfoPitchUp(int i) { return word(LFO_PITCH_UP, i); }
    short lfoPitchDown(int i) { return word(LFO_PITCH_DOWN, i); }
    short lfoDepthUp(int i) { return word(LFO_DEPTH_UP, i); }
    short lfoDepthDown(int i) { return word(LFO_DEPTH_DOWN, i); }
    short lfoRate(int i) { return word(LFO_RATE, i); }
    /** @return Q13, 0x80 is 1 */
    short egLevel(int i) { return word(EG_LEVEL, i); }
    /** @return blocks of a stage */
    short time(int i) { return word(TIME, i); }
    /** @return Q15, how near to the target a block goes */
    short rate(int i) { return word(RATE, i); }
    /** @param i resonance * 97 + cutoff (0x10 ~ 0x70) */
    short cutoff(int i) { return word(CUTOFF, i); }
    /** @param i as {@link #cutoff} */
    short resonance(int i) { return word(RESONANCE, i); }
    /** @return 2^(i / 768) * 0x4000, 0 ~ 767 */
    short pitch(int i) { return word(PITCH, i); }

    final Reverb[] reverbs = new Reverb[REVERB_COUNT];

    // ----

    private static volatile RohmRom instance;

    /** from the dll {@link FaithRom#toolsDirectory()} points */
    public static RohmRom getInstance() throws IOException {
        if (instance == null) {
            synchronized (RohmRom.class) {
                if (instance == null) {
                    instance = new RohmRom(FaithRom.toolsDirectory().toPath().resolve(DLL));
                }
            }
        }
        return instance;
    }

    /** is there a rom to play with? */
    public static boolean isAvailable() {
        return Files.exists(FaithRom.toolsDirectory().toPath().resolve(DLL));
    }

    public RohmRom(Path dll) throws IOException {
        ByteBuffer image = ByteBuffer.wrap(Files.readAllBytes(dll)).order(ByteOrder.LITTLE_ENDIAN);
        int pe = image.getInt(0x3c);
        if (image.getInt(pe) != 0x00004550) throw new IOException("not a PE: " + dll);
        int timestamp = image.getInt(pe + 8);
        if (timestamp != TIMESTAMP) {
            throw new IOException("unknown build of %s: time stamp 0x%08x, 0x%08x is known".formatted(dll, timestamp, TIMESTAMP));
        }
        int sectionCount = image.getShort(pe + 6) & 0xffff;
        int optionalSize = image.getShort(pe + 20) & 0xffff;
        int imageBase = image.getInt(pe + 24 + 28);
        ByteBuffer data = null;
        int dataVa = 0;
        for (int i = 0; i < sectionCount; i++) {
            int o = pe + 24 + optionalSize + 40 * i;
            byte[] name = new byte[8];
            image.get(o, name);
            if (new String(name).trim().startsWith(".data")) {
                int va = image.getInt(o + 12), rawSize = image.getInt(o + 16), rawOffset = image.getInt(o + 20);
                data = image.slice(rawOffset, rawSize).order(ByteOrder.LITTLE_ENDIAN);
                dataVa = imageBase + va;
            }
        }
        if (data == null) throw new IOException("no .data: " + dll);
        if (dataVa != DATA) throw new IOException("unexpected .data at 0x%08x".formatted(dataVa));

        memory = new byte[data.limit()];
        data.get(0, memory);
        for (int i = 0; i < REVERB_COUNT; i++) {
            int o = REVERB - DATA + i * 0x38;
            int[] delays = new int[8];
            for (int j = 0; j < 8; j++) delays[j] = data.getInt(o + 8 + j * 4);
            reverbs[i] = new Reverb(data.getDouble(o), delays, data.getInt(o + 0x28), data.getInt(o + 0x2c), data.getInt(o + 0x30));
        }

        patchPrograms();
logger.log(Level.DEBUG, "rom: " + dll);
    }

    /** as the dll does when it starts (0x10005320) */
    private void patchPrograms() {
        // no pan and no exclusive group
        for (int p = 0; p < PROGRAM_COUNT; p++) {
            int o = PROGRAM_TABLE + p * 6;
            memory[o + 1] = 0;
            memory[o + 3] &= 0x0f;
            memory[o + 5] &= 0x0f;
        }
        // the drums 128 ~ 174 (key 35 ~ 81) and 207 ~ 221 (drum program 0x19)
        patchDrums(128, DRUM_PAN, EXCLUSIVE, 47);
        patchDrums(207, DRUM_PAN_2, EXCLUSIVE_2, 15);
    }

    private void patchDrums(int first, int pans, int groups, int count) {
        for (int i = 0; i < count; i++) {
            int o = PROGRAM_TABLE + (first + i) * 6;
            memory[o + 1] = (byte) (memory[pans - DATA + i] - 0x40);
            memory[o + 3] = (byte) ((memory[o + 3] & 0x0f) | (word(groups, i) << 4));
        }
    }

    /** @return the program, null if the index is out of the table */
    public Program program(int index) {
        if (index < 0 || index >= PROGRAM_COUNT) return null;
        int o = PROGRAM_TABLE + index * 6;
        int layer0 = u16(memory, o + 2), layer1 = u16(memory, o + 4);
        return new Program(memory[o] & 0xff, memory[o + 1], layer0 >> 12,
                new int[] { (layer0 & 0xfff) == 0xfff ? -1 : layer0 & 0xfff, (layer1 & 0xfff) == 0xfff ? -1 : layer1 & 0xfff });
    }

    // ----

    static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }
}
