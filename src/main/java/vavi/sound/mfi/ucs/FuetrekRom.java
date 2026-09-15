/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import vavi.sound.mfi.faith.FaithType4Player;

import static java.lang.System.getLogger;


/**
 * The preset tones of the fuetrek sound source, read out of the installed
 * {@code rt_synth_4.dll} (faith "Ring Tone Authoring Tool"), nothing of it is
 * distributed with this.
 * <p>
 * The dll is a 32 bit PE, the tones are in its {@code .data}:
 * <pre>
 * group        0x204  id(1) pad(3) instrument*[128]         0x79 melody, 0x78 drum, 0x7d, 0x14
 * instrument   0x08   lowKey(1) highKey(1) 0(1) zoneCount(1) zone*
 * zone         0x44   keyHigh(1) pad(3) sampleA*(4) sampleB*(4) parameters...
 * sample       0x24   pcm*(4) length(4) loopStart(4, 20.12) loopEnd(4, 20.12) tune(2, /1024) rootKey(2) name(16)
 * </pre>
 * The groups are found by that structure, the small tables around them are at
 * the addresses of the build known ({@link #TIMESTAMP}).
 * <p>
 * system property
 * <li>{@code vavi.sound.mfi.faith.path} ... the authoring tool's {@code Tools} directory, see {@link FaithType4Player}</li>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
public final class FuetrekRom {

    private static final Logger logger = getLogger(FuetrekRom.class.getName());

    /** the PE time stamp of the {@code rt_synth_4.dll} the table addresses are of (2003-08-28) */
    static final int TIMESTAMP = 0x3f4d685e;

    public static final int GROUP_MELODY = 0x79;
    public static final int GROUP_DRUM = 0x78;

    private static final int GROUP_SIZE = 0x204;
    private static final int GROUP_ENTRIES = 128;
    private static final int INSTRUMENT_SIZE = 0x08;
    private static final int ZONE_SIZE = 0x44;
    private static final int SAMPLE_SIZE = 0x24;

    /** a pcm wave */
    public static final class Sample {
        public final String name;
        /** signed 8 bit */
        public final byte[] pcm;
        public final int loopStart;
        public final int loopEnd;
        /** fine tune, 1024 is none */
        public final int tune;
        public final int rootKey;
        /** 0: pcm, 1, 2: noise as oscillator B */
        public final int controlByte;

        public Sample(String name, byte[] pcm, int loopStart, int loopEnd, int tune, int rootKey, int controlByte) {
            this.name = name;
            this.pcm = pcm;
            this.loopStart = loopStart;
            this.loopEnd = loopEnd;
            this.tune = tune;
            this.rootKey = rootKey;
            this.controlByte = controlByte;
        }

        @Override
        public String toString() {
            return "Sample[" + name + ", length=" + pcm.length + ", loop=" + loopStart + ".." + loopEnd + ", root=" + rootKey + ", tune=" + tune + "]";
        }
    }

    /** a key range of an instrument, the envelope and the rest are in {@link #raw} */
    public static final class Zone {
        /** as it is in the dll, the sample pointers included */
        public final byte[] raw;
        public final Sample sampleA;
        public final Sample sampleB;

        Zone(byte[] raw, Sample sampleA, Sample sampleB) {
            this.raw = raw;
            this.sampleA = sampleA;
            this.sampleB = sampleB;
        }

        public int keyHigh() {
            return raw[0] & 0xff;
        }

        int s8(int offset) {
            return raw[offset];
        }

        int s16(int offset) {
            return (short) ((raw[offset] & 0xff) | ((raw[offset + 1] & 0xff) << 8));
        }

        int s32(int offset) {
            return (raw[offset] & 0xff) | ((raw[offset + 1] & 0xff) << 8) | ((raw[offset + 2] & 0xff) << 16) | (raw[offset + 3] << 24);
        }
    }

    /** a program of a group */
    public static final class Instrument {
        public final int lowKey;
        public final int highKey;
        public final Zone[] zones;

        Instrument(int lowKey, int highKey, Zone[] zones) {
            this.lowKey = lowKey;
            this.highKey = highKey;
            this.zones = zones;
        }

        /** @return the zone for the key, null if the instrument does not sound there (the native one sees no high key) */
        public Zone zone(int key) {
            if (key < lowKey) return null;
            for (Zone zone : zones) {
                if (key <= zone.keyHigh()) return zone;
            }
            return null;
        }
    }

    private final Map<Integer, Instrument[]> groups = new HashMap<>();

    /** 12 pitch ratios of the semitones in an octave */
    public final int[] pitchRatio;
    public final short[] interpolation;
    public final int[] panLaw;
    public final byte[] drumPan;
    /** first key of {@link #rootKey} */
    public final int rootKeyStart = 0x1d;
    public final byte[] rootKey;
    /** mode, record (0x79, 0x78, 0x7d, 0x14), index */
    public final int[][][] mixProfile = new int[3][4][128];
    public final int[] gainCurve;
    public final int[] stereoCurve;
    /** the envelope and control curves */
    public final int[][] curves;
    /** note map (128) then the note shape scalers */
    public final byte[] noteShape;

    /** @return the instrument, null if none */
    public Instrument instrument(int group, int program) {
        Instrument[] instruments = groups.get(group);
        return instruments == null || program < 0 || program >= GROUP_ENTRIES ? null : instruments[program];
    }

    /** @return group ids */
    public int[] groups() {
        return groups.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /** @return 15 bit gain of a 7 bit level */
    public int gainWord(int level) {
        return gainCurve[Math.clamp(level, 0, 0x7f)];
    }

    /** @return 15 bit gain of a signed pan -64 ~ 64 */
    public int stereoWord(int pan) {
        return stereoCurve[Math.clamp(pan + 0x40, 0, stereoCurve.length - 1)];
    }

    /** @return the 7 bit level whose gain is the nearest to the amplitude */
    public int gainByte(double amplitude) {
        int target = (int) Math.min(0x7fff, Math.round(amplitude * 0x7fff));
        int result = 0;
        for (int i = 1; i < gainCurve.length; i++) {
            if (Math.abs(gainCurve[i] - target) < Math.abs(gainCurve[result] - target)) result = i;
        }
        return result;
    }

    /** @return the value of the curve the nearest above the value, as the native quantizer does */
    public int quantize(int curve, int value) {
        int[] table = curves[curve];
        int first = (short) table[0];
        int last = (short) table[table.length - 1];
        if (value <= first) return first;
        if (value >= last) return last;
        for (int i = table.length - 2; i >= 0; i--) {
            if ((short) table[i] < value) return (short) table[i + 1];
        }
        return first;
    }

    /** the curve indices of {@link #quantize(int, int)}, named after their offsets in a zone */
    public static final int CURVE_16 = 0, CURVE_18 = 1, CURVE_1A = 2, CURVE_1E = 3, CURVE_20 = 4, CURVE_24 = 5,
            CURVE_28 = 6, CURVE_2A = 7, CURVE_2C = 8, CURVE_32 = 9, CURVE_34 = 10, CURVE_36 = 11;

    /** @return the tune (1024 is none) of an encoded root key byte of a UCS pitch pair, 0 if out of range */
    public int rootKeyTune(int encoded) {
        int index = encoded - rootKeyStart;
        return index < 0 || index >= rootKey.length ? 0 : (rootKey[index] & 0xff) + 0x400;
    }

    /** @return the drum note shape index */
    public int noteShapeIndex(int note) {
        return noteShape[0x0c18 + Math.clamp(note, 0, 0x7f)] & 0xff;
    }

    public int scaleForward(int word, int shape) {
        return (word * noteShapeWord(0x0c98 + (shape << 1))) >>> 15;
    }

    public int scaleReverse(int word, int shape) {
        return (word * noteShapeWord(0x0d96 - (shape << 1))) >>> 15;
    }

    private int noteShapeWord(int offset) {
        return (noteShape[offset] & 0xff) | ((noteShape[offset + 1] & 0xff) << 8);
    }

    // ----

    private static volatile FuetrekRom instance;

    /** from the dll {@link FaithType4Player#toolsDirectory()} points */
    public static FuetrekRom getInstance() throws IOException {
        if (instance == null) {
            synchronized (FuetrekRom.class) {
                if (instance == null) {
                    instance = new FuetrekRom(FaithType4Player.toolsDirectory().toPath().resolve("rt_synth_4.dll"));
                }
            }
        }
        return instance;
    }

    /** the whole dll */
    private final ByteBuffer image;
    private int imageBase;
    private int dataVa, dataSize, dataOffset;
    private final int[][] sections;

    public FuetrekRom(Path dll) throws IOException {
        image = ByteBuffer.wrap(Files.readAllBytes(dll)).order(ByteOrder.LITTLE_ENDIAN);

        int pe = image.getInt(0x3c);
        if (image.getInt(pe) != 0x00004550) throw new IOException("not a PE: " + dll);
        int timestamp = image.getInt(pe + 8);
        int sectionCount = image.getShort(pe + 6) & 0xffff;
        int optionalSize = image.getShort(pe + 20) & 0xffff;
        imageBase = image.getInt(pe + 24 + 28);
        sections = new int[sectionCount][];
        for (int i = 0; i < sectionCount; i++) {
            int o = pe + 24 + optionalSize + 40 * i;
            String name = new String(bytes(o, 8)).trim();
            int virtualSize = image.getInt(o + 8), va = image.getInt(o + 12), rawSize = image.getInt(o + 16), rawOffset = image.getInt(o + 20);
            sections[i] = new int[] { va, Math.max(virtualSize, rawSize), rawSize, rawOffset };
            if (name.startsWith(".data")) {
                dataVa = imageBase + va;
                dataSize = rawSize;
                dataOffset = rawOffset;
            }
        }
        if (dataVa == 0) throw new IOException("no .data: " + dll);

        findGroups();
        if (!groups.containsKey(GROUP_MELODY)) throw new IOException("no melody group in " + dll);

        if (timestamp != TIMESTAMP) {
            throw new IOException("unknown build of rt_synth_4.dll: time stamp 0x%08x, 0x%08x is known".formatted(timestamp, TIMESTAMP));
        }
        pitchRatio = u32s(0x1000f2e4, 12);
        interpolation = s16s(0x10012d20, 1025);
        panLaw = u16s(0x10011cc8, 128);
        drumPan = bytesAt(0x10011c48, 128);
        rootKey = bytesAt(0x1000f210, 100);
        int[] mixVas = { 0x10011030, 0x10011438, 0x10011840 };
        int[] records = { 0x79, 0x78, 0x7d, 0x14 };
        for (int mode = 0; mode < 3; mode++) {
            for (int record = 0; record < 4; record++) {
                int va = mixVas[mode] + record * 0x102;
                if ((u8(va) != records[record]) || u8(va + 1) != 0) {
                    throw new IOException("unexpected mix profile header at 0x%08x".formatted(va));
                }
                mixProfile[mode][record] = u16s(va + 2, 128);
            }
        }
        gainCurve = u16s(0x10012388, 128);
        stereoCurve = u16s(0x10012488, 129);
        int[][] curveVas = {
                { 0x10011ed8, 32 }, { 0x10011e90, 32 }, { 0x10011f20, 32 }, { 0x10011f68, 62 },
                { 0x10012060, 63 }, { 0x10011fe8, 56 }, { 0x100121f0, 32 }, { 0x10012238, 97 },
                { 0x100120e8, 128 }, { 0x10012300, 46 }, { 0x10011e08, 63 }, { 0x10011dc8, 29 } };
        curves = new int[curveVas.length][];
        for (int i = 0; i < curveVas.length; i++) {
            curves[i] = u16s(curveVas[i][0], curveVas[i][1]);
        }
        noteShape = bytesAt(0x10011030, 0x0d98);
logger.log(Level.DEBUG, "rom: " + dll + ", groups: " + groups.keySet());
    }

    /** a group is an id followed by 128 pointers to instruments, the melody group has all of them */
    private void findGroups() {
        Map<Integer, Sample> samples = new HashMap<>();
        for (int va = dataVa; va + GROUP_SIZE <= dataVa + dataSize; va += 4) {
            if (u8(va) != GROUP_MELODY || u8(va + 1) != 0 || u16(va + 2) != 0) continue;
            Instrument[] melody = group(va, samples);
            if (melody == null) continue;
            groups.put(GROUP_MELODY, melody);
            for (int next = va + GROUP_SIZE; next + GROUP_SIZE <= dataVa + dataSize; next += GROUP_SIZE) {
                int id = u8(next);
                if (id == 0) break;
                Instrument[] instruments = group(next, samples);
                if (instruments == null) break;
                groups.put(id, instruments);
            }
            return;
        }
    }

    /** @return null if it is not a group */
    private Instrument[] group(int va, Map<Integer, Sample> samples) {
        Instrument[] instruments = new Instrument[GROUP_ENTRIES];
        Map<Integer, Instrument> read = new HashMap<>();
        int count = 0;
        for (int i = 0; i < GROUP_ENTRIES; i++) {
            int pointer = u32(va + 4 + i * 4);
            if (pointer == 0) continue;
            if (!inData(pointer, INSTRUMENT_SIZE)) return null;
            Instrument instrument = read.get(pointer);
            if (instrument == null) {
                instrument = instrument(pointer, samples);
                if (instrument == null) return null;
                read.put(pointer, instrument);
            }
            instruments[i] = instrument;
            count++;
        }
        return count == 0 ? null : instruments;
    }

    private Instrument instrument(int va, Map<Integer, Sample> samples) {
        int lowKey = u8(va), highKey = u8(va + 1), zoneCount = u8(va + 3);
        int zones = u32(va + 4);
        if (u8(va + 2) != 0 || lowKey > highKey || highKey > 127 || zoneCount == 0 || !inData(zones, zoneCount * ZONE_SIZE)) return null;
        Zone[] result = new Zone[zoneCount];
        for (int z = 0; z < zoneCount; z++) {
            int zone = zones + z * ZONE_SIZE;
            Sample a = sample(u32(zone + 4), samples);
            Sample b = sample(u32(zone + 8), samples);
            if (a == null && u32(zone + 4) != 0) return null;
            result[z] = new Zone(bytesAt(zone, ZONE_SIZE), a, b);
        }
        return new Instrument(lowKey, highKey, result);
    }

    private Sample sample(int va, Map<Integer, Sample> samples) {
        if (va == 0 || !inData(va, SAMPLE_SIZE)) return null;
        Sample sample = samples.get(va);
        if (sample == null) {
            int pcm = u32(va), length = u32(va + 4);
            if (!inData(pcm, length)) return null;
            String name = new String(bytesAt(va + 0x14, 16)).split("\0", 2)[0];
            sample = new Sample(name, bytesAt(pcm, length), u32(va + 8) >>> 12, u32(va + 12) >>> 12, u16(va + 0x10), u16(va + 0x12) & 0xff, u16(va + 0x12) >> 8);
            samples.put(va, sample);
        }
        return sample;
    }

    // ----

    private boolean inData(int va, int length) {
        return Integer.compareUnsigned(va, dataVa) >= 0 && Integer.compareUnsigned(va + length, dataVa + dataSize) <= 0;
    }

    private int offset(int va) {
        int rva = va - imageBase;
        for (int[] s : sections) {
            if (rva >= s[0] && rva - s[0] < s[2]) return s[3] + rva - s[0];
        }
        throw new IllegalArgumentException("va 0x%08x is not in the file".formatted(va));
    }

    private byte[] bytes(int offset, int length) {
        byte[] result = new byte[length];
        image.get(offset, result);
        return result;
    }

    private byte[] bytesAt(int va, int length) {
        return bytes(offset(va), length);
    }

    private int u8(int va) {
        return image.get(offset(va)) & 0xff;
    }

    private int u16(int va) {
        return image.getShort(offset(va)) & 0xffff;
    }

    private int u32(int va) {
        return image.getInt(offset(va));
    }

    private int[] u16s(int va, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = u16(va + i * 2);
        return result;
    }

    private short[] s16s(int va, int count) {
        short[] result = new short[count];
        for (int i = 0; i < count; i++) result[i] = (short) u16(va + i * 2);
        return result;
    }

    private int[] u32s(int va, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = u32(va + i * 4);
        return result;
    }
}
