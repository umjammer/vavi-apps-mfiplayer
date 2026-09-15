/*
 * Copyright (c) 2026 by nattolecats, All rights reserved.
 */

package vavi.sound.mfi.ucs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import vavi.sound.mfi.InvalidMfiDataException;


/**
 * DoCoMo UCS (Universal Characteristic Sound) message sequencer.
 * <p>
 * UCS is a set of user wave tones embedded in the MFi file. What is known from
 * fuetrek files ({@code *_FT.mld}), where the vendor/carrier byte is {@code 0x71}
 * (sharp, whose sound source is fuetrek's), panasonic ones ({@code 0x41}) use fuetrek's too,
 * see {@link UcsFunction}:
 * <pre>
 * 0x10 wave     number, type 1: length(3) loopStart(3) loopEnd(3)
 *               number, type 2: length(2) signed 8 bit pcm...
 * 0x11 params   number, 0x02, length(1) = 0x2c, params...
 *               the voice parameters of the sound source, see FuetrekVoice.Template
 *               params[6] root key, params[7] encoded tune
 * 0x12 admin    number, 0x00, length(1) = 0x04, 0x80 0x00 bank program
 *               the wave is played by the notes of the mfi (bank, program)
 * </pre>
 * The waves are played at 32 kHz, the rate of the sound source.
 */
public final class UcsSequencer {

    /** the wave bank is written by the sequencer and read by the synthesizer, not always by the same thread */
    private static final UcsWaveBank waveBank = new UcsWaveBank();

    /** Package-visible for focused decoding tests. */
    public static UcsWaveBank waveBank() {
        return waveBank;
    }

    /** Stateful UCS wave packets, shared by the ServiceLoader-created functions. */
    public static final class UcsWaveBank {
        private static final int HEADER = 7;
        private final Wave[] waves = new Wave[256];
        private final Part[] parts = new Part[256];

        /**
         * Records an UCS part definition.  In the target file these are the
         * three DoCoMo UCS tone IDs {@code 0x81}, {@code 0x82}, and
         * {@code 0x83}; ordinary MFi program changes alone do not identify
         * these custom PCM tones.
         */
        public void setPart(byte[] data) throws InvalidMfiDataException {
            if (data.length < HEADER + 1) {
                throw new InvalidMfiDataException("truncated UCS part message");
            }
            int number = data[7] & 0xff;
            Part part = parts[number];
            if (part == null) {
                part = new Part();
                parts[number] = part;
            }
            part.number = number;
            part.data = Arrays.copyOfRange(data, HEADER, data.length);
        }

        /** forgets the waves of the previous song */
        public synchronized void clear() {
            Arrays.fill(waves, null);
        }

        public synchronized void setWave(byte[] data) throws InvalidMfiDataException {
            if (data.length < HEADER + 2) {
                throw new InvalidMfiDataException("truncated UCS wave message");
            }
            int number = data[7] & 0xff;
            int type = data[8] & 0xff;
            Wave wave = wave(number);

            if (type == 1) {
                if (data.length < HEADER + 11) {
                    throw new InvalidMfiDataException("truncated UCS wave definition: " + number);
                }
                wave.length = unsigned24(data, 9);
                wave.loopStart = unsigned24(data, 12);
                wave.loopEnd = unsigned24(data, 15);
                wave.data = null;
            } else if (type == 2) {
                if (data.length < HEADER + 4) {
                    throw new InvalidMfiDataException("truncated UCS wave data: " + number);
                }
                int length = unsigned16(data, 9);
                int available = data.length - 11;
                if (length > available) {
                    throw new InvalidMfiDataException("truncated UCS wave data: " + number + ", declared=" + length + ", available=" + available);
                }
                byte[] packet = Arrays.copyOfRange(data, 11, 11 + length);
                wave.data = wave.data == null ? packet : append(wave.data, packet);
                if (wave.length != 0 && wave.data.length > wave.length) {
                    wave.data = Arrays.copyOf(wave.data, wave.length);
                }
            } else {
                throw new InvalidMfiDataException("unsupported UCS wave packet type: " + type);
            }
        }

        public synchronized void setParameters(byte[] data) throws InvalidMfiDataException {
            if (data.length < HEADER + 3) {
                throw new InvalidMfiDataException("truncated UCS wave parameters");
            }
            int length = data[9] & 0xff;
            int available = data.length - (HEADER + 3);
            if (length > available) {
                throw new InvalidMfiDataException("truncated UCS wave parameters: declared=" + length + ", available=" + available);
            }
            Wave wave = wave(data[7] & 0xff);
            wave.parameters = Arrays.copyOfRange(data, HEADER + 3, HEADER + 3 + length);
            if (length >= 8) {
                // [6] root key, [7] the tune of it encoded, see FuetrekRom#rootKeyTune
                wave.rootPitch = wave.parameters[6] & 0x7f;
            }
        }

        public synchronized void setAdminStatus(byte[] data) throws InvalidMfiDataException {
            if (data.length < HEADER + 1) {
                throw new InvalidMfiDataException("truncated UCS admin status");
            }
            Wave wave = wave(data[7] & 0xff);
            wave.enabled = true;
            if (data.length >= HEADER + 7 && (data[9] & 0xff) >= 4) {
                wave.bank = data[12] & 0x3f;
                wave.program = data[13] & 0x3f;
            }
        }

        /**
         * @param midiProgram the midi program an mfi (bank, program) is converted to
         * @return the playable waves of the tone, empty if the program is not a UCS one
         * @see vavi.sound.mfi.vavi.MidiContext#toProgram(int, int)
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

        synchronized Wave wave(int number) {
            Wave wave = waves[number];
            if (wave == null) {
                wave = new Wave();
                waves[number] = wave;
            }
            return wave;
        }

        Part part(int number) {
            return parts[number];
        }

        private static int unsigned16(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        }

        private static int unsigned24(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 16) | ((data[offset + 1] & 0xff) << 8) | (data[offset + 2] & 0xff);
        }

        private static byte[] append(byte[] left, byte[] right) {
            byte[] result = Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, result, left.length, right.length);
            return result;
        }
    }

    public static final class Wave {
        int length;
        int loopStart;
        int loopEnd;
        /** MIDI note the wave sounds at when it is played as it is. */
        double rootPitch = 60;
        /** UCS wave parameter packet, retained for envelope/filter decoding. */
        byte[] parameters;
        /** derived from the loop lengths and root keys of fuetrek files, not written in them */
        int sampleRate = 32_000;
        boolean enabled;
        /** mfi bank the wave is played at */
        int bank;
        /** mfi program the wave is played at, -1 is not assigned */
        int program = -1;
        byte[] data;

        /** the data in the amplitude of the rom waves, which are 6 bit in 8 */
        private byte[] pcm;

        /** @return the wave as the sound source plays it, TODO the shift is not confirmed against the dll */
        synchronized byte[] pcm() {
            if (pcm == null || pcm.length != data.length) {
                pcm = new byte[data.length];
                for (int i = 0; i < data.length; i++) pcm[i] = (byte) (data[i] >> 2);
            }
            return pcm;
        }

        boolean isPlayable() {
            return enabled && data != null && data.length > 0;
        }
    }

    /** Raw UCS part-definition packet retained until its waveform mapping is resolved. */
    static final class Part {
        int number;
        byte[] data;
    }
}
