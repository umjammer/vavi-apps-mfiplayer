/*
 * Copyright (c) 2026 by nattolecats, All rights reserved.
 */

package vavi.sound.fuetrek;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * The user waves a song brings along, beside the preset tones of {@link FuetrekRom}.
 * <p>
 * The bank is written where the waves come from ({@code vavi.sound.mfi.fuetrek.UcsSequencer} decodes
 * the UCS messages of a song into it) and read by {@link UcsAudioEngine}, which plays a wave
 * instead of a preset tone when the (bank, program) a note sounds at is one a wave is assigned to.
 * The waves are played at 32 kHz, the rate of the sound source.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano out of UcsSequencer <br>
 */
public final class UcsWaveBank {

    /** the bank is written by the sequencer and read by the synthesizer, not always by the same thread */
    private static final UcsWaveBank instance = new UcsWaveBank();

    /** the one bank a song is decoded into */
    public static UcsWaveBank getInstance() {
        return instance;
    }

    private final Wave[] waves = new Wave[256];

    /** forgets the waves of the previous song */
    public synchronized void clear() {
        Arrays.fill(waves, null);
    }

    /** the wave of a number, made when it is the first packet of it */
    public synchronized Wave wave(int number) {
        Wave wave = waves[number];
        if (wave == null) {
            wave = new Wave();
            waves[number] = wave;
        }
        return wave;
    }

    /**
     * @param midiProgram the midi program a (bank, program) of a song is converted to,
     *                    {@code vavi.sound.mfi.vavi.MidiContext#toProgram}
     * @return the playable waves of the tone, empty if the program is not a UCS one
     */
    public synchronized List<Wave> tone(int midiProgram) {
        List<Wave> result = new ArrayList<>();
        for (Wave wave : waves) {
            if (wave != null && wave.isPlayable() && wave.program >= 0 &&
                    (((wave.bank & 0x01) << 6) | wave.program) == midiProgram) {
                result.add(wave);
            }
        }
        return result;
    }

    /**
     * @param bank the bank of a song
     * @param program the program of a song, 0 ~ 63
     * @return the playable waves of the tone, empty if it is not a UCS one
     */
    public synchronized List<Wave> tone(int bank, int program) {
        List<Wave> result = new ArrayList<>();
        for (Wave wave : waves) {
            if (wave != null && wave.isPlayable() && wave.bank == bank && wave.program == program) {
                result.add(wave);
            }
        }
        return result;
    }

    /** a user wave, as it is decoded out of a song */
    public static final class Wave {
        /** the length declared, 0: not declared */
        public int length;
        public int loopStart;
        public int loopEnd;
        /** MIDI note the wave sounds at when it is played as it is. */
        public double rootPitch = 60;
        /** the voice parameter packet of the wave, retained for envelope/filter decoding. */
        public byte[] parameters;
        /** derived from the loop lengths and root keys of fuetrek files, not written in them */
        public int sampleRate = 32_000;
        public boolean enabled;
        /** the bank the wave is played at */
        public int bank;
        /** the program the wave is played at, -1 is not assigned */
        public int program = -1;
        public byte[] data;

        /** the data in the amplitude of the rom waves, which are 6 bit in 8 */
        private byte[] pcm;

        /** @return the wave as the sound source plays it, TODO the shift is not confirmed against the dll */
        public synchronized byte[] pcm() {
            if (pcm == null || pcm.length != data.length) {
                pcm = new byte[data.length];
                for (int i = 0; i < data.length; i++) pcm[i] = (byte) (data[i] >> 2);
            }
            return pcm;
        }

        public boolean isPlayable() {
            return enabled && data != null && data.length > 0;
        }
    }
}
