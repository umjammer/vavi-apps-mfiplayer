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

    /** @return the wave of a number, null if the song has none of it */
    public synchronized Wave find(int number) {
        return waves[number & 0xff];
    }

    /**
     * @param midiProgram the midi program a (bank, program) of a song is converted to,
     *                    {@code vavi.sound.mfi.vavi.MidiContext#toProgram}
     * @return the playable waves of the tone, empty if the program is not a UCS one
     */
    public synchronized List<Wave> tone(int midiProgram) {
        List<Wave> result = new ArrayList<>();
        for (Wave wave : waves) {
            if (wave != null && wave.isPlayable() && !wave.drum && wave.program >= 0 &&
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
            if (wave != null && wave.isPlayable() && !wave.drum && wave.bank == bank && wave.program == program) {
                result.add(wave);
            }
        }
        return result;
    }

    /**
     * @param bank the bank of a song
     * @param key the note of a percussion channel of a song, the midi key - 35
     * @return the playable drum waves of the note, empty if it is not a UCS one
     */
    public synchronized List<Wave> drum(int bank, int key) {
        List<Wave> result = new ArrayList<>();
        for (Wave wave : waves) {
            if (wave != null && wave.isPlayable() && wave.drum && wave.bank == bank && wave.program == key) {
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
        /**
         * the bytes of {@link #parameters} a song has written, null: the whole of them
         * (a whole record). The MFi 5 writer writes a part at a time, see {@link #setParameters(int, byte[])}.
         */
        public boolean[] written;
        /** derived from the loop lengths and root keys of fuetrek files, not written in them */
        public int sampleRate = 32_000;
        public boolean enabled;
        /** a drum voice: {@link #program} is the note of a percussion channel it is played by */
        public boolean drum;
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
            return enabled && (data != null && data.length > 0 || isPreset());
        }

        /** the 44 bytes of the voice parameters */
        public static final int PARAMETERS_LENGTH = 44;

        /**
         * writes a part of the voice parameters
         * @param offset where in the 44 bytes
         */
        public synchronized void setParameters(int offset, byte[] value) {
            if (offset == 0 && value.length >= PARAMETERS_LENGTH) {
                parameters = value;
                written = null;
            } else {
                if (parameters == null || parameters.length < PARAMETERS_LENGTH) {
                    parameters = new byte[PARAMETERS_LENGTH];
                    written = new boolean[PARAMETERS_LENGTH];
                } else if (written == null) {
                    parameters = parameters.clone(); // what the song wrote before is kept, as notes on may still read it
                } else {
                    parameters = parameters.clone();
                    written = written.clone();
                }
                int length = Math.min(value.length, PARAMETERS_LENGTH - offset);
                System.arraycopy(value, 0, parameters, offset, length);
                if (written != null) Arrays.fill(written, offset, offset + length, true);
            }
            if (isWritten(6, 1)) {
                // [6] root key, [7] the tune of it encoded, see FuetrekRom#rootKeyTune
                rootPitch = parameters[6] & 0x7f;
            }
        }

        /** @return true if the song wrote the bytes of the voice parameters */
        public synchronized boolean isWritten(int offset, int length) {
            if (parameters == null || parameters.length < offset + length) return false;
            if (written == null) return true;
            for (int i = offset; i < offset + length; i++) {
                if (!written[i]) return false;
            }
            return true;
        }

        /**
         * a voice of a preset tone of the rom instead of a wave: [0] bit 0 (an uploaded wave) is clear,
         * the tone is the (bank, program) of [3], [4]. The MFi 5 writer writes such a voice as the
         * parameter 0x10, the voice parameters [0] ~ [5] alone.
         */
        public synchronized boolean isPreset() {
            return isWritten(0, 5) && (parameters[0] & 0x01) == 0;
        }

        /** the bank of the preset tone, {@link #isPreset()} only */
        public synchronized int presetBank() {
            return parameters[3] & 0x3f;
        }

        /** the program of the preset tone, {@link #isPreset()} only */
        public synchronized int presetProgram() {
            return parameters[4] & 0x7f;
        }

        /**
         * the voice of oscillator B, [8] link. It is only one when [9] (the balance of the two
         * oscillators) is not 0: then it is the next voice, one whose [0] bit 1 says it is the second
         * of a pair, in the 30 of 30 of the MFi 5 corpus, where [8] of the others says nothing.
         * @return -1: none, oscillator B is the one of this voice
         */
        public synchronized int link() {
            if (!isWritten(8, 2) || parameters[9] == 0) return -1;
            return parameters[8] & 0xff;
        }
    }
}
