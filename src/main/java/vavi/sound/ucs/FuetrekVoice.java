/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ucs;

import vavi.sound.ucs.FuetrekRom.Sample;
import vavi.sound.ucs.FuetrekRom.Zone;


/**
 * A note of the fuetrek sound source: two 8 bit pcm oscillators (or noise) through
 * a tone shaping filter, an amplitude envelope (A), a filter envelope (B) and an
 * lfo, run at 32 kHz in the fixed point arithmetic of the native synthesizer.
 * <p>
 * The control rate is a 128 frame block, {@link #render(int)} is given the frame
 * counter of the block.
 * <p>
 * behaviour as recovered by openDoJa's {@code FueTrekSampler}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
final class FuetrekVoice {

    /** the parameters a voice starts with, from a rom zone or a UCS parameter packet */
    static final class Template {
        int ampA, ampB;
        int zoneGain;
        int modMode, modA, mod8, modScale2, modDelay;
        int envA6, envA8, envAA, envAE;
        int envB2, envB4, envB6, envBA, envBC, envBE, envB10;
        int shapeMode, shapeW2, shapeW4;

        /** the zone layout of the dll */
        static Template of(Zone zone) {
            Template t = new Template();
            t.ampA = zone.s16(0x0c);
            t.ampB = zone.s16(0x0e);
            t.zoneGain = zone.s8(0x14);
            t.shapeMode = zone.s8(0x15);
            t.envB2 = zone.s16(0x16);
            t.envB6 = zone.s16(0x18);
            t.shapeW2 = zone.s16(0x1a);
            t.envB4 = zone.s16(0x1e);
            t.shapeW4 = zone.s16(0x20);
            t.envBA = zone.s8(0x22);
            t.envBC = zone.s16(0x24);
            t.envA6 = zone.s16(0x28);
            t.envA8 = zone.s16(0x2a);
            t.envAA = zone.s16(0x2c);
            t.envAE = zone.s8(0x2f);
            t.modMode = zone.s8(0x30);
            t.modA = zone.s16(0x32);
            t.mod8 = zone.s16(0x34);
            t.modScale2 = zone.s16(0x36);
            t.modDelay = zone.s8(0x38);
            t.envBE = zone.s8(0x40);
            t.envB10 = zone.s16(0x42);
            return t;
        }

        /**
         * the 44 byte voice parameters of a UCS wave ({@code 0x11}), which are the
         * parameters of the native "voice edit" control
         * <pre>
         *  0 flags (bit 0: an uploaded wave), 1 wave, 2 ~ 5 the preset derived from
         *  6 root key, 7 encoded tune, 8 link, 9 oscillator balance, 10 gain(2)
         * 12 env A(7), 19 env B(7), 26 shape mode, 27 shape w4(2), 29 shape w2(2), 31 env B level(2)
         * 33 key track: key, amount(2), 36 lfo mode, 37 lfo speed(2), 39 lfo depth(2), 41 lfo amp(2), 43 lfo delay
         * </pre>
         */
        static Template of(FuetrekRom rom, byte[] p) {
            Template t = new Template();
            if (p.length < 44) return t;
            int balance = p[9] & 0xff;
            if (balance < 0x80) {
                t.ampA = 0x1ff;
                t.ampB = balance << 2;
            } else {
                t.ampA = (0xff - balance) << 2;
                t.ampB = 0x1ff;
            }
            if (isPair(p, 10)) t.zoneGain = Math.clamp(signed14(p, 10) >> 8, 0, 0x1f) << 1;
            if (isPair(p, 12) && isPair(p, 14) && isPair(p, 16) && p[18] >= 0) {
                t.envA6 = rom.quantize(FuetrekRom.CURVE_28, signed14(p, 12) >> 2);
                t.envA8 = rom.quantize(FuetrekRom.CURVE_2A, signed14(p, 14) >> 2);
                t.envAA = rom.quantize(FuetrekRom.CURVE_2C, signed14(p, 16) >> 2);
                t.envAE = Math.clamp(((p[18] & 0x7f) - 0x40) >> 1, 0, 0x1f);
            }
            if (isPair(p, 19) && isPair(p, 21) && isPair(p, 23) && p[25] >= 0) {
                t.envB2 = rom.quantize(FuetrekRom.CURVE_16, signed14(p, 19) >> 2);
                t.envB4 = rom.quantize(FuetrekRom.CURVE_1E, signed14(p, 21) >> 2);
                t.envB6 = rom.quantize(FuetrekRom.CURVE_18, signed14(p, 23) >> 2);
                t.envBA = Math.clamp(((p[25] & 0x7f) - 0x40) >> 1, 0, 0x1f);
            }
            if (p[26] >= 0 && p[26] <= 2) t.shapeMode = p[26];
            if (isPair(p, 27)) t.shapeW4 = rom.quantize(FuetrekRom.CURVE_20, signed14(p, 27) >> 2);
            if (isPair(p, 29)) t.shapeW2 = rom.quantize(FuetrekRom.CURVE_1A, signed14(p, 29) >> 2);
            if (isPair(p, 31)) t.envBC = rom.quantize(FuetrekRom.CURVE_24, signed14(p, 31) >> 2);
            if ((p[33] & 0x7f) >= 0x15 && (p[33] & 0x7f) <= 0x78 && isPair(p, 34)) {
                t.envBE = p[33] & 0x7f;
                t.envB10 = Math.clamp(signed14(p, 34) >> 7, -0x40, 0x3f);
            }
            if (p[36] >= 0 && p[36] <= 3) t.modMode = p[36];
            if (isPair(p, 37)) t.modA = rom.quantize(FuetrekRom.CURVE_32, signed14(p, 37) >> 4);
            if (isPair(p, 39)) t.mod8 = rom.quantize(FuetrekRom.CURVE_34, signed14(p, 39) >> 4);
            if (isPair(p, 41)) t.modScale2 = rom.quantize(FuetrekRom.CURVE_36, signed14(p, 41) >> 4);
            if (p[43] >= 0 && p[43] <= 0x13) t.modDelay = p[43];
            return t;
        }

        private static boolean isPair(byte[] p, int offset) {
            return (p[offset] & 0xc0) == 0;
        }

        private static int signed14(byte[] p, int offset) {
            return (((p[offset] & 0x3f) << 8) | (p[offset + 1] & 0xff)) - 0x2000;
        }
    }

    // ----

    final FuetrekRom rom;
    final UcsAudioEngine.Channel channel;
    /** the midi key it was started by */
    final int key;
    /** the note byte the zone was resolved by */
    final int note;
    final int group;
    final int program;
    final long age;
    final int velocity;

    private final Oscillator oscA, oscB;
    private final int rootA, rootB, tuneA, tuneB;
    private final int ampA, ampB;
    private final int oscBMode;
    private final int zoneGain;
    private final int baseNote;
    private final int drumPan;
    private final Lfo lfo;
    private final EnvelopeA envA;
    private final EnvelopeB envB;
    private final Shape shape;
    private final int[] noise = new int[16];

    /** bend and tunings of the channel [Q16 semitones] */
    private int pitchQ16;
    /** the pedal held it after its note off */
    boolean held;
    private int noteCoarse, noteFine;
    private int mixLeft, mixRight;

    int left, right;

    /**
     * @param rootOffset zone +0x10
     * @param tuneOffset zone +0x12, added to B
     * @param fixedNote zone +0x3c, -1 is none
     */
    FuetrekVoice(FuetrekRom rom, UcsAudioEngine.Channel channel, int key, int note, int velocity, int group, int program,
                 Sample sampleA, Sample sampleB, int rootOffset, int tuneOffset, int fixedNote, Template template, long age) {
        this.rom = rom;
        this.channel = channel;
        this.key = key;
        this.note = note;
        this.velocity = velocity;
        this.group = group;
        this.program = program;
        this.age = age;
        if (sampleB == null) sampleB = sampleA;
        this.drumPan = group == FuetrekRom.GROUP_DRUM ? rom.drumPan[note] & 0x7f : 0x40;
        this.baseNote = fixedNote != -1 ? fixedNote : note;
        this.rootA = sampleA.rootKey + rootOffset;
        this.rootB = sampleB.rootKey + rootOffset;
        this.tuneA = sampleA.tune;
        this.tuneB = sampleB.tune + tuneOffset;
        this.ampA = template.ampA;
        this.ampB = template.ampB;
        this.oscBMode = sampleB.controlByte;
        this.zoneGain = template.zoneGain;
        this.lfo = new Lfo(template);
        this.envA = new EnvelopeA(template);
        this.envB = new EnvelopeB(template);
        this.shape = new Shape(template);
        java.util.Arrays.fill(noise, 1);
        this.oscA = new Oscillator(sampleA);
        this.oscB = new Oscillator(sampleB);
        lfo.seed(channel.modulation());
        pitchQ16 = channel.pitchQ16();
        updatePitch();
        updateMix();
    }

    void release() {
        envA.state = 6;
        envB.state = 6;
    }

    boolean isReleased() {
        return envA.state >= 6 || envA.state == 0;
    }

    /** @param q16 the pitch of the channel, bend and tunings [Q16 semitones] */
    void pitch(int q16) {
        pitchQ16 = q16;
    }

    /** stops at once */
    void stop() {
        envA.state = 0;
    }

    void modulation(int seed) {
        lfo.seed(seed);
    }

    /**
     * renders a frame into {@link #left} and {@link #right}
     * @param counter frame counter in the 128 frame control block
     * @return false when the voice has ended
     */
    boolean render(int counter) {
        if (envA.state == 0) {
            left = right = 0;
            return false;
        }
        updatePitch();
        updateMix();
        shiftNoise(noise);
        lfo.step(counter);
        int a = envA.step(counter, lfo);
        int b = envB.step(counter, noteCoarse, shape.w4);

        int mixed = 0;
        if (ampA > 0) {
            mixed = clamp16(mulRound(ampA, oscA.next(noteCoarse, rootA, noteFine, tuneA, lfo.pitch(), rom), 9));
        }
        int valueB = switch (oscBMode) {
            case 1 -> noiseA(noise);
            case 2 -> noiseB(noise);
            default -> oscB.next(noteCoarse, rootB, noteFine, tuneB, lfo.pitch(), rom);
        };
        if (ampB > 0) {
            mixed = clamp16(mixed + clamp16(mulRound(ampB, valueB, 9)));
        }
        int shaped = shape.apply((short) (b >> 4), (short) mixed);
        int level = clamp16(mulRound(zoneGain << 5, a, 11));
        int l = clamp16(mulRound((mixLeft >> 2) & ~3, level, 11));
        int r = clamp16(mulRound((mixRight >> 2) & ~3, level, 11));
        left = clamp16(mulRound((l >> 3) & ~7, shaped, 9)) << 1;
        right = clamp16(mulRound((r >> 3) & ~7, shaped, 9)) << 1;
        if (envA.state == 0) {
            left = right = 0;
            return false;
        }
        return true;
    }

    private void updatePitch() {
        int q16 = pitchQ16;
        int coarse, fine;
        if (q16 < 0) {
            int negative = -q16;
            int fraction = (0x10000 - (negative & 0xffff)) >> 11;
            coarse = ((fraction >> 5) - (negative >> 16)) + baseNote - 1;
            fine = fraction & 0x1f;
        } else {
            coarse = (q16 >> 16) + baseNote;
            fine = (q16 >> 11) & 0x1f;
        }
        noteCoarse = Math.clamp(coarse, 0x15, 0x7f);
        noteFine = fine;
    }

    private void updateMix() {
        UcsAudioEngine.Mix mix = channel.mix;
        int gain = mulWord(rom.gainWord(channel.volume), rom.gainWord(mix.globalVolume()));
        gain = mulWord(gain, rom.gainWord(velocity));
        gain = mulWord(gain, rom.gainWord(channel.expression));
        gain = mulWord(gain, rom.gainWord(0x7f));
        gain = mulWord(gain, rom.gainWord(0x40));

        int leftWord = mulWord(mulWord(gain, rom.stereoWord(-mix.masterPan)), rom.stereoWord(-channel.pan));
        int rightWord = mulWord(mulWord(gain, rom.stereoWord(mix.masterPan)), rom.stereoWord(channel.pan));
        if (group == FuetrekRom.GROUP_DRUM) {
            leftWord = ((leftWord & 0xffff) * rom.panLaw[0x7f - drumPan]) >> 15;
            rightWord = ((rightWord & 0xffff) * rom.panLaw[drumPan]) >> 15;
            int shapeIndex = rom.noteShapeIndex(note);
            leftWord = rom.scaleReverse(leftWord, shapeIndex);
            rightWord = rom.scaleForward(rightWord, shapeIndex);
        }
        mixLeft = Math.clamp((long) leftWord << 1, 0, 0x7fff);
        mixRight = Math.clamp((long) rightWord << 1, 0, 0x7fff);
    }

    // ----

    /** 4 point interpolated 8 bit pcm, the phase is 20.12 */
    private static final class Oscillator {
        final Sample sample;
        int phase;

        Oscillator(Sample sample) {
            this.sample = sample;
        }

        int next(int note, int root, int fine, int tune, int pitchMod, FuetrekRom rom) {
            int step = step(note, root, fine, tune, pitchMod, rom.pitchRatio);
            if (step <= 0 || sample.pcm.length < 4) return 0;
            int index = phase >> 12;
            int fraction = ((phase >> 3) & 0x1ff) + 0x200;
            short[] table = rom.interpolation;
            int mixed = table[fraction] * sampleAt(index)
                    + table[fraction - 0x200] * sampleAt(index + 1)
                    + table[0x400 - fraction] * sampleAt(index + 2)
                    + table[0x600 - fraction] * sampleAt(index + 3);
            mixed >>= 9;

            phase += step;
            int loopStart = sample.loopStart << 12;
            int loopLength = (sample.loopEnd << 12) - loopStart;
            if (loopLength > 0 && phase >= sample.loopEnd << 12) {
                phase = loopStart + Math.floorMod(phase - loopStart, loopLength);
            }
            return (short) mixed;
        }

        /** the phase increment at 32 kHz */
        private static int step(int note, int root, int fine, int tune, int pitchMod, int[] ratios) {
            int delta = (fine >> 5) - root + note;
            int octave = delta / 12;
            int semitone = delta % 12;
            int step = (((fine & 0x1f) + 0x200) * tune) << 1;
            step = (step + ((step >> 31) & 0x3ff)) >> 10;
            step *= pitchMod + 0x200;
            step = (step + ((step >> 31) & 0x1ff)) >> 9;
            int scaled = (((short) step) * ratios[Math.floorMod(semitone, ratios.length)] >> 7) & ~7;
            if (note > root) {
                if (semitone != 0) octave++;
                if (octave >= 30) scaled = Integer.MAX_VALUE;
                else if (octave > 0) scaled <<= octave;
            } else if (octave < 0) {
                scaled >>= -octave;
            }
            return Math.max(0, scaled);
        }

        private int sampleAt(int index) {
            byte[] pcm = sample.pcm;
            if (index >= 0 && index < pcm.length) return pcm[index];
            int loopLength = sample.loopEnd - sample.loopStart;
            if (loopLength <= 0) return 0;
            return pcm[Math.clamp(sample.loopStart + Math.floorMod(index - sample.loopStart, loopLength), 0, pcm.length - 1)];
        }
    }

    /** triangle, square, fall and rise, after a delay */
    private static final class Lfo {
        int value, delay, tick, depth, speed, output, pitch, mode, direction = 1, amplitude;
        final int baseDepth;

        Lfo(Template t) {
            mode = t.modMode;
            value = switch (mode) {
                case 2 -> 0x1ff;
                case 3 -> (short) 0xfe01;
                default -> 0;
            };
            delay = t.modDelay;
            baseDepth = t.mod8;
            depth = baseDepth;
            speed = t.modA;
            amplitude = t.modScale2;
        }

        void seed(int seed) {
            depth = baseDepth + (seed >> 2);
        }

        int pitch() {
            return (short) pitch;
        }

        void step(int counter) {
            if (counter == 0) {
                if ((short) delay == 0) {
                    switch (mode) {
                    case 0, 1 -> {
                        value = (short) (value + (short) speed * direction);
                        if (Math.abs((short) value) >= 0x1ff) {
                            value = (short) (direction * 0x1ff);
                            direction = -direction;
                        }
                        output = mode == 0 ? (short) value : (short) (direction * 0x1ff);
                    }
                    case 2 -> {
                        value = (short) value > (short) speed ? (short) ((short) value - (short) speed) : 0;
                        output = (short) value;
                    }
                    case 3 -> {
                        value = (short) value + (short) speed < 0 ? (short) ((short) value + (short) speed) : 0;
                        output = (short) value;
                    }
                    default -> {}
                    }
                } else {
                    tick = (tick + 1) & 0xff;
                    if (tick == 0x18) {
                        tick = 0;
                        delay = (short) (delay - 1);
                    }
                    output = mode == 1 ? (short) (direction * 0x1ff) : (short) value;
                }
            }
            pitch = (short) (clamp16(roundShift((((short) output) << 6) * (short) depth, 9)) >> 6);
        }
    }

    /** the amplitude envelope, states 1, 2 start, 3 attack, 4 decay, 5 sustain, 6 release, 7 quick release, 0 end */
    private static final class EnvelopeA {
        int current, target, previous, attack, decay, release, sustain;
        int state = 1;

        EnvelopeA(Template t) {
            attack = t.envA6 << 4;
            decay = t.envA8;
            release = t.envAA;
            sustain = t.envAE << 10;
        }

        int step(int counter, Lfo lfo) {
            switch (state) {
            case 0, 1 -> {
                if ((counter & 1) == 0) {
                    state++;
                    previous = (short) target;
                    int delta = -((counter << 1) * (short) previous);
                    current = (short) clamp16((delta >> 8) + (delta < 0 ? 1 : 0) + (short) previous);
                    target = 0;
                }
            }
            case 2 -> {
                if ((counter & 1) == 0) {
                    previous = target = current = (short) attack;
                    state = 3;
                }
            }
            case 3 -> {
                if ((counter & 0x7f) == 0) {
                    move(counter, clamp16(mulSignRound((short) target, 0x7ff, 11) + (short) attack));
                    if ((short) target >= 0x7c00) {
                        if ((short) current >= 0x7c00) current = previous = target = 0x7c00;
                        state = 4;
                    }
                }
            }
            case 4 -> {
                if ((counter & 0x7f) == 0) {
                    move(counter, clamp16(mulSignRound((short) decay, (short) target, 11)));
                    if ((short) target <= (short) sustain) {
                        target = (short) sustain;
                        state = 5;
                    }
                }
            }
            case 5 -> {
                if ((counter & 0x7f) == 0) previous = current = (short) target;
            }
            case 6 -> {
                if ((counter & 0x7f) == 0) {
                    move(counter, clamp16(mulSignRound((short) release, (short) target, 11)));
                    if ((short) current <= 0) {
                        state = 0;
                        current = 0;
                    }
                }
            }
            case 7 -> {
                if ((counter & 1) == 0) {
                    move(counter, clamp16(mulSignRound((short) target, 0x7dc, 11)));
                    if ((short) current <= 0) {
                        state = 0;
                        current = 0;
                    }
                }
            }
            default -> {}
            }

            int tremolo = clamp16(mulRound(lfo.output << 6, ((short) current) >> 4, 11));
            tremolo = clamp16(mulRound(lfo.amplitude << 2, tremolo, 11));
            return (short) clamp16(tremolo + clamp16(mulRound((short) current, 0x7ff, 11)));
        }

        /** steps the block target, interpolating the current within the block */
        private void move(int counter, int next) {
            previous = (short) target;
            target = (short) next;
            current = (short) clamp16(mulSignRound((short) target - (short) previous, counter << 1, 8) + (short) previous);
        }
    }

    /** the filter envelope with key tracking */
    private static final class EnvelopeB {
        int value, attack, decay, release, sustain, level, trackKey, trackAmount;
        int state = 1;

        EnvelopeB(Template t) {
            attack = t.envB2 << 4;
            decay = t.envB4;
            release = t.envB6;
            sustain = t.envBA << 10;
            level = t.envBC;
            trackKey = t.envBE;
            trackAmount = t.envB10 << 4;
        }

        int step(int counter, int note, int offset) {
            switch (state) {
            case 0, 1 -> {
                if ((counter & 1) == 0) {
                    state++;
                    value = 0;
                }
            }
            case 2, 3 -> {
                if ((counter & (state == 2 ? 1 : 0x7f)) == 0) {
                    value = (short) clamp16(mulSignRound((short) value, 0x7ff, 11) + (short) attack);
                    if ((short) value >= 0x7c00) {
                        state = 4;
                        value = 0x7c00;
                    } else {
                        state = 3;
                    }
                }
            }
            case 4 -> {
                if ((counter & 0x7f) == 0) {
                    value = (short) clamp16(mulSignRound((short) decay, (short) value, 11));
                    if ((short) value <= (short) sustain) {
                        value = (short) sustain;
                        state = 5;
                    }
                }
            }
            case 6 -> {
                if ((counter & 0x7f) == 0) {
                    value = (short) clamp16(mulSignRound((short) release, (short) value, 11));
                    if ((short) value <= 0) {
                        state = 0;
                        value = 0;
                    }
                }
            }
            case 7 -> {
                if ((counter & 1) == 0) {
                    value = (short) clamp16(mulSignRound((short) value, 0x7dc, 11));
                    if ((short) value <= 0) {
                        state = 0;
                        value = 0;
                    }
                }
            }
            default -> {}
            }

            int track = clamp16(roundShift(((trackKey - (note & 0xff)) * trackAmount) << 7, 8) + offset);
            return (short) clamp16(mulRound((short) level, (short) value, 11) + track);
        }
    }

    /** a state variable filter, mode 0: low pass, 1: high pass, 2: band pass */
    private static final class Shape {
        final int mode, w2, w4;
        int band, low;

        Shape(Template t) {
            mode = t.shapeMode;
            w2 = clamp16(t.shapeW2);
            w4 = clamp16(t.shapeW4 << 4);
        }

        int apply(short cutoff, short input) {
            int nextLow = clamp16(mulRound(band, cutoff, 10) + low);
            int high = clamp16(mulRound(w2, band, 10) - nextLow + input);
            int nextBand = clamp16(mulRound(cutoff, high, 10) + band);
            band = nextBand;
            low = nextLow;
            return switch (mode) {
                case 1 -> high;
                case 2 -> nextBand;
                default -> nextLow;
            };
        }
    }

    // ----

    /** a 16 bit lfsr, a bit an element, taps at 1, 3 and 12 */
    private static void shiftNoise(int[] n) {
        int x = n[15];
        System.arraycopy(n, 0, n, 1, 15);
        n[12] ^= x;
        n[3] ^= x;
        n[1] ^= x;
        n[0] = x;
    }

    private static int noiseA(int[] n) {
        return ((n[0] != 0 ? -32 : 0) | n[1] << 4 | n[2] << 3 | n[3] << 2 | n[4] << 1 | n[5]) << 6;
    }

    private static int noiseB(int[] n) {
        return ((n[2] != 0 ? -32 : 0) | n[3] << 4 | n[4] << 3 | n[5] << 2 | n[6] << 1 | n[7]) << 6;
    }

    static int clamp16(int value) {
        return Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    private static int roundShift(int value, int shift) {
        return (value >> shift) + ((value >> (shift - 1)) & 1);
    }

    private static int mulRound(int a, int b, int shift) {
        return roundShift(a * b, shift);
    }

    private static int mulSignRound(int a, int b, int shift) {
        int product = a * b;
        return (product >> shift) + (product < 0 ? 1 : 0);
    }

    private static int mulWord(int a, int b) {
        return (a * b) >> 15;
    }
}
