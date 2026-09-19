/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import java.util.Arrays;


/**
 * The rohm sound source in pure java, what {@code rt_synth_2.dll} is: a midi synthesizer of
 * 64 voices at 44.1 kHz, 128 frames at a time.
 * <p>
 * A port of the dll, which is the synthesizer ({@code RTPSynthOpen}) over a driver
 * ({@link RohmDriver}) over the voices of the chip ({@link RohmLsi}), and a reverb
 * ({@link RohmReverb}) after them. What is sent before a {@link #render(int[])} is heard from
 * the second block after it, as the dll does.
 * <p>
 * What it takes of midi:
 * <ul>
 * <li>bank select msb ... the group, latched by a program change: 0x79 melody, 0x7d (6 tones),
 *     0x11 (8 tones), an even one a drum set, 0x14 is the second drum set, 0 is the channel's default</li>
 * <li>a drum set plays key 35 ~ 81 (the drum program 0x19 has one of its own for 35 ~ 50),
 *     key 0 ~ 6 the UCS drums, the second set key 35 ~ 66</li>
 * <li>1 modulation, 7 volume, 10 pan, 11 expression, 64 hold, 120 / 123 cut the notes,
 *     121 reset all controllers, rpn 0 bend sensitivity by 6 / 38 / 96 / 97</li>
 * <li>pitch bend, gm system on, universal master volume, balance, fine and coarse tuning</li>
 * </ul>
 * a key struck again is struck again, a note off of a drum is not taken but for the long whistle
 * and the long guiro.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public final class RohmSoundSource {

    public static final int SAMPLE_RATE = 44100;
    /** frames a {@link #render(int[])} makes */
    public static final int BLOCK = RohmLsi.BLOCK;
    public static final int CHANNELS = 16;
    public static final int POLYPHONY = RohmLsi.VOICES;

    private static final int DRUM_CHANNEL = 9;

    /** {@link RohmRom#memory}, the UCS is written into it */
    private final byte[] memory;
    private final RohmLsi lsi;
    private final RohmDriver driver;
    private final RohmReverb reverb;

    // a channel (0x2c bytes of the dll)

    /** signed 14 bit */
    private final int[] bend = new int[CHANNELS];
    /** semitones << 7 | cents */
    private final int[] sensitivity = new int[CHANNELS];
    /** the group, latched */
    private final int[] bank = new int[CHANNELS];
    /** bank select msb */
    private final int[] bankPending = new int[CHANNELS];
    private final int[] program = new int[CHANNELS];
    private final int[] volume = new int[CHANNELS];
    private final int[] modulation = new int[CHANNELS];
    /** signed */
    private final int[] pan = new int[CHANNELS];
    private final int[] expression = new int[CHANNELS];
    /** 1: rpn, 2: nrpn */
    private final int[] parameterType = new int[CHANNELS];
    private final int[] rpnMsb = new int[CHANNELS], rpnLsb = new int[CHANNELS], nrpnMsb = new int[CHANNELS], nrpnLsb = new int[CHANNELS];

    // the universal device controls

    private int masterVolume = 0x7f;
    /** signed */
    private int masterBalance;
    /** signed 14 bit */
    private int masterFine;
    private int masterCoarse;

    /** Q13 */
    private final int gain = 0x2000;

    public RohmSoundSource(RohmRom rom) {
        this.memory = rom.memory.clone();
        this.lsi = new RohmLsi(memory);
        this.driver = new RohmDriver(rom, memory, lsi);
        this.reverb = new RohmReverb(rom);
        // as RTPSynthOpen and opening a slot leave it
        resetChannels();
        lsi.reset();
        driver.reset();
        controlChange(DRUM_CHANNEL, 0, 0x78);
        reverb.select(-1);
        open();
    }

    /** a slot opened: the voices off, the master controls to their defaults (0x10002050) */
    public void open() {
        lsi.reset();
        lsi.master = 0x7f00;
        lsi.shift = -7;
        masterVolume = 0x7f;
        masterBalance = 0;
        masterFine = 0;
        masterCoarse = 0;
    }

    /** gm system on (0x10001700) */
    private void resetChannels() {
        for (int c = 0; c < CHANNELS; c++) {
            bank[c] = bankPending[c] = c != DRUM_CHANNEL ? 0x79 : 0x78;
            program[c] = 0;
            sensitivity[c] = 0x100;
            volume[c] = 0x7f;
            bend[c] = 0;
            modulation[c] = 0;
            pan[c] = 0;
            expression[c] = 0x7f;
            parameterType[c] = 0;
            rpnMsb[c] = rpnLsb[c] = nrpnMsb[c] = nrpnLsb[c] = 0x7f;
            driver.resetChannel(c);
            driver.program(c, (bank[c] & 1) != 0 ? 0 : RohmDriver.DRUM);
        }
        reverb.select(0);
    }

    /**
     * a channel message
     * @param status with the channel
     */
    public void shortMessage(int status, int data1, int data2) {
        int c = status & 0x0f;
        data1 &= 0x7f;
        data2 &= 0x7f;
        switch (status & 0xf0) {
        case 0x90 -> {
            if (data2 != 0) {
                noteOn(c, data1, data2);
            } else {
                noteOff(c, data1);
            }
        }
        case 0x80 -> noteOff(c, data1);
        case 0xb0 -> controlChange(c, data1, data2);
        case 0xc0 -> programChange(c, data1);
        case 0xe0 -> {
            bend[c] = (short) ((data2 << 7 | data1) + 0xe000);
            updateBend(c);
        }
        default -> {}
        }
    }

    private void noteOn(int c, int key, int velocity) {
        int drumKey = drumKey(c, key);
        if (drumKey != Integer.MIN_VALUE) driver.noteOn(c, drumKey, velocity);
    }

    private void noteOff(int c, int key) {
        int drumKey = drumKey(c, key);
        if (drumKey != Integer.MIN_VALUE) driver.noteOff(c, drumKey);
    }

    /** @return the key the driver takes, {@link Integer#MIN_VALUE}: none */
    private int drumKey(int c, int key) {
        if ((bank[c] & 1) != 0) return key;
        if (key < 7) return key + 100;
        if (key < 8) return Integer.MIN_VALUE;
        if (bank[c] == 0x14) {
            return key >= 0x23 && key <= 0x42 ? key + 0x0c : Integer.MIN_VALUE;
        }
        if (program[c] == 0x19) {
            if (key == 0x23 || key == 0x24) return key + 0x2c;
            if (key > 0x25 && key < 0x33) return key + 0x2b;
        } else if (key < 0x23) {
            return Integer.MIN_VALUE;
        }
        if (key > 0x51) return Integer.MIN_VALUE;
        int k = key - 0x23;
        // the dll plays a melody program for those, below the lowest key of it: nothing
        return k < 0 ? Integer.MIN_VALUE : k;
    }

    private void controlChange(int c, int control, int value) {
        switch (control) {
        case 0 -> bankPending[c] = value != 0 ? value : (c != DRUM_CHANNEL ? 0x79 : 0x78);
        case 1 -> {
            modulation[c] = value;
            driver.modulation(c, value);
        }
        case 6 -> {
            if (isBendSensitivity(c)) sensitivity[c] = value << 7;
        }
        case 7 -> {
            volume[c] = value;
            updateVolume(c);
        }
        case 10 -> {
            pan[c] = (byte) (value - 0x40);
            updatePan(c);
        }
        case 11 -> {
            expression[c] = value;
            updateVolume(c);
        }
        case 38 -> {
            if (isBendSensitivity(c)) sensitivity[c] = (sensitivity[c] + value) & 0xffff;
        }
        case 64 -> driver.hold(c, value >= 0x40);
        case 96 -> {
            if (isBendSensitivity(c)) {
                sensitivity[c] = (sensitivity[c] + 0x80) & 0xffff;
                if (sensitivity[c] > 0x3fff) sensitivity[c] = 0x3fff;
            }
        }
        case 97 -> {
            if (isBendSensitivity(c)) sensitivity[c] = (sensitivity[c] - 0x80) & 0xffff;
        }
        case 98 -> {
            nrpnLsb[c] = value;
            if (value != 0x7f) parameterType[c] = 2;
        }
        case 99 -> {
            nrpnMsb[c] = value;
            if (value != 0x7f) parameterType[c] = 2;
        }
        case 100 -> {
            rpnLsb[c] = value;
            if (value != 0x7f) parameterType[c] = 1;
        }
        case 101 -> {
            rpnMsb[c] = value;
            if (value != 0x7f) parameterType[c] = 1;
        }
        case 120, 123 -> driver.cut(c);
        case 121 -> {
            driver.resetChannel(c);
            bend[c] = 0;
            expression[c] = 0x7f;
            modulation[c] = 0;
            // the dll leaves the pan hard right to what the master balance makes of it
            pan[c] = 0x40;
            parameterType[c] = 0;
            rpnMsb[c] = rpnLsb[c] = nrpnMsb[c] = nrpnLsb[c] = 0x7f;
            for (int i = 0; i < CHANNELS; i++) driver.hold(i, false);
        }
        default -> {}
        }
    }

    private boolean isBendSensitivity(int c) {
        return parameterType[c] == 1 && ((rpnMsb[c] << 8) | rpnLsb[c]) == 0;
    }

    private void programChange(int c, int value) {
        int bank = bankPending[c];
        this.bank[c] = bank;
        program[c] = value;
        if ((bank & 1) == 0) {
            driver.program(c, RohmDriver.DRUM);
        } else if (bank == 0x11) {
            driver.program(c, value < 8 ? value + 0xe4 : 0xff);
        } else if (bank == 0x79) {
            driver.program(c, value);
        } else if (bank == 0x7d) {
            driver.program(c, value < 6 ? value + 0xde : 0xff);
        }
    }

    private void updateVolume(int c) {
        driver.volume(c, ((volume[c] * expression[c]) >> 7) * masterVolume >> 7);
    }

    private void updatePan(int c) {
        driver.pan(c, Math.clamp(masterBalance + 0x40 + pan[c], 0, 0x7f));
    }

    private void updateBend(int c) {
        driver.bend(c, ((masterFine + bend[c]) * sensitivity[c]) >> 14);
    }

    /**
     * an exclusive, f0 ... f7: gm system on (device 0x7f only) and the universal device
     * controls, the rest is not taken
     * @return false: not taken
     */
    public boolean exclusive(byte[] data) {
        if (data.length < 5 || (data[0] & 0xff) != 0xf0) return false;
        int type = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
        if (data[1] == 0x7e) {
            if (data[2] == 0x7f && type == 0x0901) {
                resetChannels();
                return true;
            }
        } else if (data[1] == 0x7f && data.length >= 7) {
            int lsb = data[5] & 0xff, msb = data[6] & 0xff;
            switch (type) {
            case 0x0401 -> {
                masterVolume = ((msb << 7) | lsb) >> 7;
                for (int c = 0; c < CHANNELS; c++) updateVolume(c);
            }
            case 0x0402 -> {
                masterBalance = msb - 0x40;
                for (int c = 0; c < CHANNELS; c++) updatePan(c);
            }
            case 0x0403 -> {
                masterFine = ((msb << 7) | lsb) - 0x2000;
                for (int c = 0; c < CHANNELS; c++) updateBend(c);
            }
            case 0x0404 -> {
                masterCoarse = msb - 0x40;
                driver.transpose(masterCoarse);
            }
            default -> {
                return false;
            }
            }
            return true;
        }
        return false;
    }

    /** @param preset 0 ~ 7, -1: off, the reverb (the 12th entry of the dll's synthesizer) */
    public void reverb(int preset) {
        if (preset < -1 || preset >= RohmRom.REVERB_COUNT) throw new IllegalArgumentException("preset: " + preset);
        reverb.select(preset);
    }

    // ---- UCS, the parameters 0x10001 ~ 0x10003 of the dll's synthesizer

    /** the wave headers of UCS as the dll takes them, 10 bytes a wave */
    private static final int UCS_WAVE_HEADERS = 10 * 10;
    /** the zones of UCS */
    private static final int UCS_ZONES = 8 * 0x20;

    /**
     * pcm of UCS into the ram of the wave memory (0x10001)
     * @param pcm signed 8 bit
     * @param offset in the ram [bytes], a multiple of 4, the address of a wave header is
     *        {@code (0x20000 + offset) / 4}
     */
    public void ucsPcm(byte[] pcm, int offset) {
        int at = RohmRom.WAVE_RAM + (offset & ~3);
        if (offset < 0 || at + pcm.length > RohmRom.WAVE_MEMORY_SIZE) throw new IllegalArgumentException("offset: " + offset);
        System.arraycopy(pcm, 0, memory, RohmRom.WAVE_DATA + at, pcm.length);
    }

    /**
     * the wave headers of UCS, the waves 192 ~ 201 (0x10002)
     * @param data big endian words, a wave is address / 4, loop start, end, pitch low, pitch high
     * @param offset in the headers [bytes]
     */
    public void ucsWaves(byte[] data, int offset) {
        int length = data.length & ~1;
        if (offset < 0 || offset + length > UCS_WAVE_HEADERS) throw new IllegalArgumentException("offset: " + offset);
        // the headers without their pads, as the dll gathers them
        byte[] packed = new byte[UCS_WAVE_HEADERS];
        for (int w = 0; w < 10; w++) {
            int o = RohmRom.WAVE_TABLE + (RohmRom.UCS_WAVE + w) * 12;
            System.arraycopy(memory, o, packed, w * 10, 6);
            System.arraycopy(memory, o + 8, packed, w * 10 + 6, 4);
        }
        for (int i = 0; i < length; i += 2) {
            packed[offset + i] = data[i + 1];
            packed[offset + i + 1] = data[i];
        }
        for (int w = 0; w < 10; w++) {
            int o = RohmRom.WAVE_TABLE + (RohmRom.UCS_WAVE + w) * 12;
            System.arraycopy(packed, w * 10, memory, o, 6);
            System.arraycopy(packed, w * 10 + 6, memory, o + 8, 4);
        }
    }

    /**
     * the zones of UCS, 576 ~ 583, the programs 228 ~ 235 (0x10003): bank 0x11 program 0 ~ 7,
     * a drum set key 0 ~ 6
     * @param data zones, 32 bytes each
     * @param offset in the zones [bytes]
     */
    public void ucsZones(byte[] data, int offset) {
        if (offset < 0 || offset + data.length > UCS_ZONES) throw new IllegalArgumentException("offset: " + offset);
        System.arraycopy(data, 0, memory, RohmRom.ZONE_TABLE + RohmRom.UCS_ZONE * 0x20 + offset, data.length);
    }

    // ---- audio

    /** @return the voices sounding */
    public int voices() {
        int count = 0;
        for (short flag : lsi.flags) if (flag != 0) count++;
        return count;
    }

    /**
     * a block
     * @param out {@link #BLOCK} frames of 16 bit stereo, written
     */
    public void render(int[] out) {
        lsi.render();
        driver.update(lsi.status);
        short[] mix = lsi.mix;
        for (int i = 0; i < BLOCK * 2; i += 2) {
            reverb.process(mix, i);
            out[i] = (mix[i] * gain) >> 13;
            out[i + 1] = (mix[i + 1] * gain) >> 13;
        }
    }

    /** all notes off at once */
    public void reset() {
        Arrays.fill(lsi.flags, (short) 0);
        driver.reset();
    }
}
