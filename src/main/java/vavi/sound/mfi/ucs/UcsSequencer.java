/*
 * Copyright (c) 2026 by nattolecats, All rights reserved.
 */

package vavi.sound.mfi.ucs;

import java.util.Arrays;

import vavi.sound.mfi.InvalidMfiDataException;


/**
 * DoCoMo UCS (Universal Characteristic Sound) message sequencer.
 *
 * <p>The vendor nibble is zero for UCS.  Consequently, its complete vendor/carrier
 * identifier is {@code 0x01}, which must not be treated as an unknown vendor.</p>
 */
public final class UcsSequencer {

    private static final ThreadLocal<UcsWaveBank> waveBank = new ThreadLocal<>();

    /** Package-visible for focused decoding tests. */
    public static UcsWaveBank waveBank() {
        if (waveBank.get() == null) waveBank.set(new UcsWaveBank());
        return waveBank.get();
    }

    /** Stateful UCS wave packets, shared by the ServiceLoader-created sequencer. */
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

        public void setWave(byte[] data) throws InvalidMfiDataException {
            if (data.length < HEADER + 2) {
                throw new InvalidMfiDataException("truncated UCS wave message");
            }
            int number = data[7] & 0xff;
            int type = data[8] & 0xff;
            Wave wave = waves[number];
            if (wave == null) {
                wave = new Wave();
                waves[number] = wave;
            }

            if (type == 1) {
                if (data.length < HEADER + 9) {
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
                    length = available;
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

        public void setParameters(byte[] data) throws InvalidMfiDataException {
            if (data.length < HEADER + 3) {
                throw new InvalidMfiDataException("truncated UCS wave parameters");
            }
            int length = data[9] & 0xff;
            int available = data.length - (HEADER + 3);
            if (length > available) {
                length = available;
                throw new InvalidMfiDataException("truncated UCS wave parameters: declared=" + length + ", available=" + available);
            }
            Wave wave = wave(data[7] & 0xff);
            wave.parameters = Arrays.copyOfRange(data, HEADER + 3, HEADER + 3 + length);
            if (length >= 8) {
                // The fixed 0x2c immediately before this block is its byte
                // length, not a MIDI key.  The tuning is an 8.8 fixed-point
                // MIDI note at parameter offsets 6 and 7.
                wave.rootPitch = unsigned16(wave.parameters, 6) / 256d;
            }
        }

        public void setAdminStatus(byte[] data) {
            if (data.length >= HEADER + 1) {
                wave(data[7] & 0xff).enabled = true;
            }
        }

        Wave wave(int number) {
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

    static final class Wave {
        int length;
        int loopStart;
        int loopEnd;
        /** UCS 8.8 fixed-point MIDI reference pitch. */
        double rootPitch = 60;
        /** UCS wave parameter packet, retained for envelope/filter decoding. */
        byte[] parameters;
        /** FueTrek's UCS-capable software synthesizer uses a 24 kHz wave table. */
        int sampleRate = 24_000;
        boolean enabled;
        byte[] data;
    }

    /** Raw UCS part-definition packet retained until its waveform mapping is resolved. */
    static final class Part {
        int number;
        byte[] data;
    }
}
