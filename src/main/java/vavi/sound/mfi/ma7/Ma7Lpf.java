/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;


/**
 * The filter of a wave table slot (ARM::CLpf): a state variable low pass of 24 bits, its cutoff
 * by an envelope of 4 stages and an lfo, which may be a random walk.
 * <p>
 * the cutoff is 13 bit ({@code << 17} in the envelope), 8 ~ 0x1ff8, to a coefficient of Q13.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Lpf {

    /** the stages of the envelope (the function pointer at +8) */
    enum Eg { DEAD, KEYON, ATTACK, DECAY, SUSTAIN, RELEASE }

    private final Ma7Noise noise;

    /** Q13 to the resonance (sdQTbl) */
    private final int[] qTable;
    /** 5 bit to a rate of the envelope (sdAlRate) */
    private final int[] rateTable;
    /** 3 bit to a rate of the lfo (dAlLfoRate) */
    private final int[] lfoRateTable;
    /** 8 lfo waves of 4096 (psdAlLfo) */
    private final int[][] lfoTables = new int[8][];
    /** 13 bit cutoff to Q13 (sdKTbl) */
    private final int[] kTable;

    int low;             // +0
    int band;            // +4
    Eg eg = Eg.DEAD;     // +8
    int cutoff;          // +0x10, << 17
    int lfoPhase;        // +0x14
    int[] lfo;           // +0x28
    int lfoRate;         // +0x34
    boolean lfoReset;  // +0x50
    int egFree;          // +0x51, the envelope takes no key off
    int releaseHold;     // +0x52
    boolean fcOffsetted; // +0x53, the offsets are added
    int resonanceOffset; // +0x54
    int brightness;      // +0x58
    int fcOffset;        // +0x5c
    int attackRate;      // +0x60
    int decayRate;       // +0x64
    int sustainRate;     // +0x68
    int releaseRate;     // +0x6c
    int q;               // +0x70
    int initialLevel;    // +0x74
    int attackLevel;     // +0x78
    int decayLevel;      // +0x7c
    int sustainLevel;    // +0x80
    int releaseLevel;    // +0x84
    int resonance;       // +0x88
    int level0, level1, level2, level3, level4; // +0x8c ~ +0x9c, as the voice tells
    int hold;            // +0xa4
    int randomLfo;       // +0xa8
    int randomDepth;     // +0xac
    int randomTarget;    // +0xb0
    int randomLevel;     // +0xb4
    int randomStep;      // +0xb8

    Ma7Lpf(Ma7Rom rom, Ma7Noise noise, int fs) {
        this.noise = noise;
        if (fs != 48000) throw new IllegalArgumentException("only 48 kHz: " + fs);
        qTable = rom.ints(0x387940, 32);
        rateTable = rom.ints(0x3878c0, 32);
        lfoRateTable = rom.ints(0x3675a0, 8);
        for (int i = 0; i < 8; i++) lfoTables[i] = rom.ints(rom.pointer(0x476fc0 + i * 8), 0x1000);
        kTable = rom.ints(0x2cf4e0, 0x2000);
        lfoRate = lfoRateTable[0];
        lfo = lfoTables[0];
        randomStep = rateTable[31];
    }

    /** the voice of the filter (ARM::_tagAlInfo) */
    record Voice(int egFree, int releaseHold, int offset1, int offset2, int resonance,
                 int level0, int level1, int level2, int level3, int level4,
                 int attack, int decay, int sustain, int release,
                 int random, int randomLfo, int lfoRate, int lfoReset) {}

    /** SetVoice */
    void setVoice(Voice v) {
        egFree = v.egFree;
        releaseHold = v.releaseHold;
        fcOffsetted = v.offset1 == 1 || v.offset2 == 1;
        resonance = v.resonance & 0x1f;
        q = qTable[clamp(resonance + resonanceOffset, 0, 0x1f)];
        attackRate = rateTable[v.attack & 0x1f];
        decayRate = rateTable[v.decay & 0x1f];
        sustainRate = rateTable[v.sustain & 0x1f];
        releaseRate = rateTable[v.release & 0x1f];
        level0 = v.level0 & 0x1fff;
        level1 = v.level1 & 0x1fff;
        level2 = v.level2 & 0x1fff;
        level3 = v.level3 & 0x1fff;
        level4 = v.level4 & 0x1fff;
        setLevels(fcOffsetted ? fcOffset : 0);
        randomLfo = v.randomLfo & 1;
        int r = lfoRateTable[v.lfoRate & 7];
        if (randomLfo != 0) r <<= 1;
        lfoRate = r;
        randomDepth = v.random & 7;
        lfo = lfoTables[randomDepth];
        lfoReset = (v.lfoReset & 1) != 0;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    /** a level of 13 bits to the envelope's, 8 ~ 0x1ff8 */
    private static int level(int v) {
        if (v < 8) return 0x100000;
        if (v < 0x1ff9) return v << 17;
        return 0x3ff00000;
    }

    private void setLevels(int offset) {
        initialLevel = level(level0 + offset);
        attackLevel = level(level1 + offset);
        decayLevel = level(level2 + offset);
        sustainLevel = level(level3 + offset + brightness);
        releaseLevel = level(level4 + offset);
    }

    /** SetResonance */
    void setResonance(int r) {
        resonanceOffset = r - 0x20;
        q = qTable[clamp(resonanceOffset + resonance, 0, 0x1f)];
    }

    /** SetBrightness */
    void setBrightness(int b) {
        brightness = (b - 0x40) * 0x80;
        int v = brightness + level3 + (fcOffsetted ? fcOffset : 0);
        sustainLevel = level(v);
    }

    /** SetFcOffset */
    void setFcOffset(int o) {
        fcOffset = o & 0x1ff8;
        setLevels(fcOffsetted ? fcOffset : 0);
    }

    /** SetHold1 */
    void setHold1(int h) {
        hold = h & 0xff;
    }

    /** GetEgMode */
    boolean isDead() {
        return eg == Eg.DEAD;
    }

    /** SetEgMode: 0 dead, 1 release, 2 key on */
    void setEgMode(int mode) {
        switch (mode) {
        case 1 -> {
            if (egFree == 0 && eg != Eg.RELEASE && eg != Eg.DEAD) eg = Eg.RELEASE;
        }
        case 0 -> {
            low = 0;
            band = 0;
            eg = Eg.DEAD;
        }
        case 2 -> {
            if (eg == Eg.RELEASE || eg == Eg.DEAD) eg = Eg.KEYON;
            low = 0;
            band = 0;
        }
        default -> {}
        }
    }

    private int random() {
        return (short) noise.generate() << (randomDepth + 7);
    }

    /** the envelope a sample */
    private void envelope() {
        switch (eg) {
        case DEAD -> {
            cutoff = 0;
            lfoPhase += lfoRate;
        }
        case KEYON -> {
            cutoff = initialLevel;
            if (lfoReset) lfoPhase = 0;
            randomTarget = randomDepth != 0 ? random() : 0;
            randomLevel = 0;
            eg = Eg.ATTACK;
        }
        case ATTACK -> {
            lfoPhase += lfoRate;
            if (attackRate != 0 && stage(attackRate, attackLevel)) eg = Eg.DECAY;
        }
        case DECAY -> {
            lfoPhase += lfoRate;
            if (decayRate != 0 && stage(decayRate, decayLevel)) eg = Eg.SUSTAIN;
        }
        case SUSTAIN -> {
            lfoPhase += lfoRate;
            if (sustainLevel > cutoff) {
                cutoff += sustainRate;
            } else if (sustainLevel < cutoff) {
                cutoff -= sustainRate;
            }
        }
        case RELEASE -> {
            lfoPhase += lfoRate;
            if (hold == 0 || releaseHold == 0) {
                if (cutoff < releaseLevel) {
                    cutoff += releaseRate;
                } else if (releaseLevel < cutoff) {
                    cutoff -= releaseRate;
                }
            }
        }
        }
    }

    /** @return the target reached */
    private boolean stage(int rate, int target) {
        int c = cutoff;
        if (c < target) {
            int v = rate + c;
            if (v < target) {
                cutoff = v;
                return false;
            }
        } else {
            if (c <= target) return true;
            int v = c - rate;
            if (target < v) {
                cutoff = v;
                return false;
            }
        }
        cutoff = target;
        return true;
    }

    /** Generate */
    int generate(int in) {
        int oldPhase = lfoPhase;
        envelope();
        int fc;
        if (randomLfo == 0) {
            fc = cutoff + (lfo[lfoPhase >>> 20] << 17);
        } else {
            if (Integer.compareUnsigned(oldPhase, lfoPhase) > 0) {
                randomTarget = randomDepth == 0 ? 0 : random();
            }
            if (randomLevel < randomTarget) {
                randomLevel += randomStep;
            } else {
                randomLevel -= randomStep;
            }
            fc = randomLevel + cutoff;
        }
        int index = fc >> 17;
        int k = kTable[index <= 7 ? 8 : Math.min(index, 0x1ff8)];
        int x = in + (int) (((long) low * q) >> 13);
        x = clamp(x, -0x800000, 0x7fffff);
        x = clamp(band + x, -0x800000, 0x7fffff);
        int l = clamp(low - (int) (((long) x * k) >> 13), -0x800000, 0x7fffff);
        low = l;
        int b = clamp(band + (int) (((long) k * l) >> 13), -0x800000, 0x7fffff);
        band = b;
        return b;
    }
}
