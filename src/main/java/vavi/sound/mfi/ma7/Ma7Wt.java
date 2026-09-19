/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import vavi.sound.mfi.ma7.Ma7Chip.Channel;
import vavi.sound.mfi.ma7.Ma7Chip.ExChannel;


/**
 * The wave table slots of the MA-7 (ARM::CWtSynth, ARM::WTCONTROL), 32 of them.
 * <p>
 * A slot's voice is 39 bytes in the wave memory ({@link #setVoiceRegister} 1 ~ 3 point at it):
 * the wave (4 bit adpcm, 8 bit signed or offset, 16 bit, or noise), its loop, an amplitude
 * envelope of 4 rates, a pitch envelope of 5 levels, the lfos to the pitch and the amplitude,
 * and a filter ({@link Ma7Lpf}). A slot sounds on a channel ({@link Channel}), which gives it
 * the volume, the pan, the sends, the pitch bend and the modulation.
 * <p>
 * the registers of a slot
 * <pre>
 * 1 ~ 3   the address of the voice / 2, 3 loads it
 * 4, 5    the cutoff offset, 13 bit
 * 6       the velocity, 5 bit, bit 0: the volume as it is
 * 7, 8    block (3), fnum (10)
 * 9       the channel (6), bit 6: the pitch of the ex channel too
 * 10      bit 6: key on, bit 5: mute, bit 4: damp
 * </pre>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Wt {

    /** the stages of the envelope (the function pointer at +0x80c8) */
    enum Eg { NONE, KEYON, ATTACK, DECAY, SUSTAIN, RELEASE }

    /** the kinds of wave (the function pointer at +0x38) */
    enum Osc { ADPCM, PCM16, PCM8A, PCM8B, NOISE }

    static final int SLOTS = 32;

    /** a slot (ARM::_tagWTSLOTINFO, 0x8188 bytes) */
    static final class Slot {
        boolean active;          // +0x08
        /** 1: voice, 2: key on, 4: key off, 8: pitch, 0x10: lfo, 0x40: hold */
        int flags;               // +0x0c
        /** 2: sounding */
        int state;               // +0x10
        /** where the wave is in the memory */
        int wave;                // +0x18, +0x20
        // adpcm
        int adpcmPosition;       // +0x28
        int adpcmStep;           // +0x2c
        int adpcmLast;           // +0x30
        /** the samples decoded */
        final short[] adpcm = new short[0x4000]; // +0x34
        Osc osc;                 // +0x38

        // the voice
        int pan;                 // +0x8040, 5 bit
        int panOff;              // +0x8041, the voice has no pan, the ex channel's only
        int mono;                // +0x8042
        int blockOffset;         // +0x8043
        int lpfOn;               // +0x8044
        int lfoRate;             // +0x8045
        int format;              // +0x8046
        int egFree;              // +0x8047, no key off
        int releaseHold;         // +0x8048
        int noise;               // +0x8049
        int keyScale;            // +0x804a
        int levelKeyScale;       // +0x804b
        int attack;              // +0x804c
        int decay;               // +0x804d
        int sustainRate;         // +0x804e
        int release;             // +0x804f
        int sustainLevel;        // +0x8050
        int totalLevel;          // +0x8051
        int vibratoScale;        // +0x8052
        int vibratoOn;           // +0x8053
        int tremoloScale;        // +0x8054
        int tremoloOn;           // +0x8055
        int loopStart;           // +0x8060
        int end;                 // +0x8064
        int pitchEgOn;           // +0x8068
        int pitchEgFree;         // +0x8069
        int pitchEgHold;         // +0x806a
        int pitchRate0, pitchRate1, pitchRate2, pitchRate3; // +0x806b ~ +0x806e
        int pitchLevel0, pitchLevel1, pitchLevel2, pitchLevel3, pitchLevel4; // +0x806f ~ +0x8073
        Ma7Lpf.Voice lpfVoice;   // +0x8074

        int block;               // +0x8098
        int fnum;                // +0x809c
        int pitch;               // +0x80a0
        int bend = 0x10000;      // +0x80a4
        int exBend = 0x10000;    // +0x80a8
        int exBend2 = 0x10000;   // +0x80ac
        int velocity = 0x8000;   // +0x80b0
        int keyOn;               // +0x80b4
        int hold;                // +0x80b5
        int modulation;          // +0x80b6
        int tremolo;             // +0x80b7
        int keyCode;             // +0x80b8
        int lfoPhase;            // +0x80c0
        Eg eg = Eg.NONE;         // +0x80c8
        int last;                // +0x80d0
        int phase;               // +0x80d8
        int step;                // +0x80d4
        boolean ended;           // +0x80dc
        int tl;                  // +0x80e0
        int tlScaled;            // +0x80e4
        int attackRate;          // +0x80e8
        int decayRate;           // +0x80ec
        int sustainRateValue;    // +0x80f0
        int releaseRate;         // +0x80f4
        int sustainLevelValue;   // +0x80f8
        int level;               // +0x80fc
        int amplitude;           // +0x8104
        int vibrato;             // +0x8108, the step with the vibrato
        int vibratoOn2;          // +0x8100
        int vibratoDepth;        // +0x8101
        int tremoloDepth;        // +0x8102
        int holdRelease;         // +0x8103
        int mute;                // +0x810c
        int damp;                // +0x810d
        int channel;             // +0x810e
        int exPitch;             // +0x810f
        int volumeAsIs;          // +0x8110
        /** 1 ~ 5 */
        int pitchEgState;        // +0x8114
        int pitchHold;           // +0x8118
        int pitchEgLevel;        // +0x811c
        int pitchEgRate0, pitchEgRate1, pitchEgRate2, pitchEgRate3; // +0x8120 ~ +0x812c
        int pitchEgLevel0, pitchEgLevel1, pitchEgLevel2, pitchEgLevel3, pitchEgLevel4; // +0x8130 ~ +0x8140
        int fcOffset;            // +0x8158
        int route;               // +0x8170
        int out;                 // +0x8171
        int dry;                 // +0x8180
        int sendA;               // +0x8184
        int sendB;               // +0x8188

        Ma7Lpf lpf;              // +0x8150
        Ma7Interpolators.Volume volumeIp; // +0x8160
        Ma7Interpolators.Pan panIp;       // +0x8168
    }

    final Slot[] slots = new Slot[SLOTS];

    private final Ma7Chip chip;
    private final Ma7Noise noise;

    /** the level shift, 0 ~ 3 (+0x103108) */
    private int shift;
    /** the level, 15 bit (+0x10310c) */
    private int volume = 0x7fff;

    // tables
    private final int[] slTable, tlTable, kso, adpcmCoef;
    private final int[] arTable, drTable, lfoRateTable, pitchRateTable;
    private final int[] pitchLevelTable, lfoPitchTable, levelTable, dbTable;
    private final int[] vibratoTable, tremoloTable;

    // the buffers of a block (statics of the library)
    private final int[] volumes = new int[32];
    private final int[] panLeft = new int[32], panRight = new int[32];
    private final int[] dryBuffer = new int[32], sendABuffer = new int[32], sendBBuffer = new int[32];

    Ma7Wt(Ma7Chip chip) {
        this.chip = chip;
        this.noise = chip.noise;
        Ma7Rom rom = chip.rom;
        if (chip.fs != 48000) throw new IllegalArgumentException("only 48 kHz: " + chip.fs);
        slTable = rom.ints(0x21fff0, 16);
        tlTable = rom.ints(0x21fef0, 64);
        kso = rom.ints(0x21f6f0, 4 * 0x80);
        adpcmCoef = rom.ints(0x220030, 8);
        arTable = rom.ints(0x21f460, 64);
        drTable = rom.ints(0x21f560, 64);
        lfoRateTable = rom.ints(0x21f660, 4);
        pitchRateTable = rom.ints(0x21f670, 32);
        pitchLevelTable = rom.ints(0x192200, 256);
        lfoPitchTable = rom.ints(0x18d0f0, 0x1000);
        levelTable = rom.ints(0x1910f0, 0x404);
        dbTable = rom.ints(0x192100, 32);
        vibratoTable = rom.ints(0x192600, 31 * 0x1000);
        tremoloTable = rom.ints(0x20e600, 4 * 0x1000);
        for (int i = 0; i < SLOTS; i++) {
            Slot s = new Slot();
            s.lpf = new Ma7Lpf(rom, noise, chip.fs);
            s.volumeIp = new Ma7Interpolators.Volume(rom, chip.fs);
            s.panIp = new Ma7Interpolators.Pan(rom, chip.fs);
            slots[i] = s;
        }
    }

    /** WTCONTROL_SetVolume */
    void setVolume(int shift, int volume) {
        if (Integer.compareUnsigned(volume, 0x7fff) > 0) volume = 0x7fff;
        this.shift = shift & 3;
        this.volume = volume;
    }

    /** WTCONTROL_SetWTVoiceReg */
    void setVoiceRegister(int slot, int address, int data, int reset) {
        if (slot >= 0x40) return;
        int[] info = chip.wtInfo[slot];
        switch (address) {
        case 1 -> info[0] = data & 3;
        case 2 -> info[1] = data & 0x7f;
        case 3 -> {
            info[2] = data & 0x7f;
            if (reset == 0) {
                loadVoice(slot, info[1] << 8 | info[0] << 15 | (data & 0x7f) << 1);
            }
        }
        case 4 -> info[3] = data & 0x3f;
        case 5 -> {
            info[4] = data & 0x7f;
            if (slot < SLOTS) slots[slot].fcOffset = ((data & 0x7f) | (info[3] & 0x3f) << 7) & 0x1fff;
        }
        case 6 -> {
            info[5] = data & 0x7d;
            if (slot < SLOTS) {
                Slot s = slots[slot];
                s.velocity = s.volumeIp.linear((data & 0x7d) >> 2);
                s.volumeAsIs = data & 1;
            }
        }
        case 7 -> info[6] = data & 0x3f;
        case 8 -> {
            info[7] = data & 0x7f;
            if (slot < SLOTS) {
                Slot s = slots[slot];
                s.fnum = (data & 0x7f) | (info[6] & 7) << 7;
                s.block = (info[6] >> 3) & 7;
                s.pitch = (s.fnum + 0x400) << (s.block + 1);
                s.flags |= 8;
            }
        }
        case 9 -> {
            info[8] = data & 0x7f;
            if (slot < SLOTS) {
                Slot s = slots[slot];
                s.channel = data & 0x3f;
                s.exPitch = (data & 0x7f) >> 6;
            }
        }
        case 10 -> {
            info[9] = data & 0x7f;
            if ((chip.regM[0][2] & 0x80) != 0 || slot < SLOTS) {
                keyOn(slot, (data >> 6) & 1, (data >> 4) & 1, (data >> 5) & 1);
            }
        }
        default -> {}
        }
    }

    /** the voice, 39 bytes of the memory, to a slot (WTCONTROL_SetWTVoiceReg 3, CWtSynth::SetVoice) */
    private void loadVoice(int slot, int address) {
        if (address >= Ma7Chip.MEMORY_SIZE) return;
        int[] p = new int[39];
        for (int i = 0; i < 39; i++) p[i] = chip.memory(address + i);
        int format = p[1] & 3;
        int wave = (p[9] << 1) | (p[8] << 9);
        if (wave >= Ma7Chip.MEMORY_SIZE) return;
        int[] shifts = { 0, 2, 1, 1 };
        int loop = ((p[10] << 8) | p[11]) & 0x7fff;
        if (wave + ((loop << shifts[format]) + 1 >> 1) >= Ma7Chip.MEMORY_SIZE) return;
        int end = ((p[12] << 8) | p[13]) & 0x7fff;
        if (wave + ((end << shifts[format]) + 1 >> 1) >= Ma7Chip.MEMORY_SIZE) return;
        if (slot >= SLOTS) return;
        Slot s = slots[slot];

        s.active = false;
        s.pan = (p[0] >> 3) & 0x1f;
        s.panOff = (p[1] >> 5) & 1;
        s.mono = (p[1] >> 4) & 1;
        s.blockOffset = p[0] & 7;
        s.lpfOn = (p[1] >> 3) & 1;
        s.lfoRate = p[1] >> 6;
        s.format = format;
        s.egFree = (p[2] >> 3) & 1;
        s.releaseHold = (p[2] >> 1) & 1;
        s.noise = (p[1] >> 2) & 1;
        s.keyScale = p[2] & 1;
        s.levelKeyScale = p[5] & 3;
        s.attack = ((p[7] >> 3) & 1) | ((p[4] >> 3) & 0x1e);
        s.decay = ((p[7] >> 2) & 1) | ((p[3] & 0xf) << 1);
        s.sustainRate = ((p[7] >> 1) & 1) | ((p[2] >> 3) & 0x1e);
        s.release = ((p[3] >> 3) & 0x1e) | (p[7] & 1);
        s.sustainLevel = p[4] & 0xf;
        s.totalLevel = p[5] >> 2;
        s.vibratoScale = 1 << ((p[6] >> 1) & 3);
        s.vibratoOn = p[6] & 1;
        s.tremoloScale = (p[6] >> 5) & 3;
        s.tremoloOn = (p[6] >> 4) & 1;
        s.lpfVoice = new Ma7Lpf.Voice(p[26] >> 7, p[27] >> 7, p[28] >> 7, p[29] >> 7, p[14] & 0x1f,
                ((p[16] & 0x1f) << 8 | p[17]) & 0x1fff, ((p[18] & 0x1f) << 8 | p[19]) & 0x1fff,
                ((p[20] & 0x1f) << 8 | p[21]) & 0x1fff, ((p[22] & 0x1f) << 8 | p[23]) & 0x1fff,
                ((p[24] & 0x1f) << 8 | p[25]) & 0x1fff,
                p[26] & 0x1f, p[27] & 0x1f, p[28] & 0x1f, p[29] & 0x1f,
                p[15] >> 5, (p[15] >> 4) & 1, p[15] & 7, (p[15] >> 3) & 1);
        s.pitchEgOn = (p[2] >> 2) & 1;
        s.pitchEgFree = p[30] >> 7;
        s.pitchEgHold = p[31] >> 7;
        s.pitchRate0 = p[30] & 0x1f;
        s.pitchRate1 = p[31] & 0x1f;
        s.pitchRate2 = p[32] & 0x1f;
        s.pitchRate3 = p[33] & 0x1f;
        s.pitchLevel0 = p[34];
        s.pitchLevel1 = p[35];
        s.pitchLevel2 = p[36];
        s.pitchLevel3 = p[37];
        s.pitchLevel4 = p[38];
        s.keyCode = 0;
        s.vibratoOn2 = s.modulation != 0 ? s.vibratoOn : 0;
        s.holdRelease = s.hold != 0 ? s.releaseHold : 0;
        s.pitchHold = s.hold != 0 ? s.pitchEgHold : 0;
        s.wave = wave;
        s.end = end;
        s.loopStart = loop;
        s.active = true;
        s.flags |= 1;
    }

    /** CWtSynth::KeyOn */
    private void keyOn(int slot, int on, int damp, int mute) {
        if (slot >= SLOTS) return;
        Slot s = slots[slot];
        s.mute = mute;
        if (s.damp == 0 && (damp & 0xff) != 0) {
            s.damp = damp;
            s.flags |= 4;
        }
        if (on == 1) {
            if (s.keyOn == 0) {
                s.keyOn = on;
                s.flags |= 2;
            }
        } else {
            if (s.keyOn == 1) {
                s.keyOn = 0;
                s.flags |= 4;
            }
        }
    }

    /** the level of 5 bits (0x1910f0, 0x192100) */
    private int level(int v) {
        return levelTable[dbTable[v & 0x1f] >>> 16];
    }

    /** CWtSynth::Generate */
    void generate(int n, int[][] bus) {
        int offset = 0;
        int remaining = n;
        while (remaining != 0) {
            int sub;
            if (remaining < 0x21) {
                sub = remaining;
                remaining = 0;
            } else {
                remaining -= 0x20;
                sub = 0x20;
            }
            for (Slot s : slots) {
                generate(s, sub, offset, bus);
            }
            offset += sub;
        }
    }

    private static boolean special(int ch) {
        return (ch >= 0x40 && ch < 0x80) || ch > 0x8c;
    }

    private void generate(Slot s, int n, int offset, int[][] bus) {
        if (s.eg == Eg.NONE && s.lpf.isDead()) {
            if (s.state == 2 && (s.flags & 2) == 0) {
                s.active = false;
                s.state = 0;
            }
        } else {
            s.state = 2;
        }
        if (!s.active) return;

        int ch = s.channel;
        boolean special = special(ch);
        Channel c = !special && ch < 0x40 ? chip.channels[ch] : null;
        ExChannel e = special ? null : chip.exChannels[c != null ? c.exId & 0xf : ch - 0x80];

        // the volume and the pan of the block
        int volume1, volume2, pan1, pan2;
        if (special) {
            volume1 = -2; volume2 = -2; pan1 = -2; pan2 = -2;
        } else if (c != null) {
            volume1 = c.volume;
            pan1 = c.pan;
            volume2 = e.volume;
            pan2 = e.pan;
        } else {
            volume1 = 0x1f;
            pan1 = 0x10;
            volume2 = e.volume;
            pan2 = e.pan;
        }
        int keyOn = s.volumeAsIs == 0 ? (s.flags >> 1) & 1 : 0;
        s.volumeIp.generate(volume1, volume2, false, keyOn, volumes, n);
        s.panIp.generate(s.pan, pan1, pan2, s.panOff != 0, keyOn, s.mono != 0, panLeft, panRight, n);

        // the sends
        int dryLevel = special ? -2 : c != null ? level(c.dry) : 0x8000;
        s.dry = (dryLevel * volume) >>> 15;
        if (special) {
            s.sendA = (volume * -2) >>> 15;
            s.sendB = (volume * -2) >>> 15;
        } else if (c != null) {
            s.sendA = (volume * level(c.sfx1)) >>> 15;
            s.sendB = (volume * level(c.sfx2)) >>> 15;
        } else {
            s.sendA = 0;
            s.sendB = 0;
        }
        // the route
        int route;
        if (special) {
            s.route = 0xc0;
            route = 0xfe;
        } else {
            if (c != null && c.exOn == 1) {
                route = c.exMode | 0x80;
            } else {
                route = e.out | e.mode << 6;
            }
            s.route = route & 0xc0;
        }
        s.out = route & 3;

        // what the channel tells
        int flags = s.flags;
        int hold, bend, exBend, exBend2, modulation, tremolo;
        if (special) {
            hold = 0xfe; bend = -2; exBend = -2; exBend2 = -2; modulation = 0xfe; tremolo = 0xfe;
        } else if (c != null) {
            hold = c.hold1; bend = c.pitchBend; exBend = e.pitchBend; exBend2 = e.pitchBend2; modulation = c.modulation; tremolo = c.tremolo;
        } else {
            hold = 0; bend = 0x10000; exBend = e.pitchBend; exBend2 = e.pitchBend2; modulation = 1; tremolo = 1;
        }
        if (special || hold != s.hold) {
            flags |= 0x40;
            s.lpf.setHold1(hold);
            s.hold = hold & 0xff;
        }
        if (s.bend != bend) {
            s.bend = bend;
            flags |= 8;
        }
        if (s.exBend != exBend) {
            s.exBend = exBend;
            flags |= 8;
        }
        if (s.exBend2 != exBend2) {
            s.exBend2 = exBend2;
            flags |= 8;
        }
        if (s.modulation != (modulation & 0xff)) {
            s.modulation = modulation & 0xff;
            flags |= 0x10;
        }
        if (s.tremolo != (tremolo & 0xff)) {
            s.tremolo = tremolo & 0xff;
            flags |= 0x10;
        }
        if ((flags & 0x40) != 0) {
            s.holdRelease = s.hold & s.releaseHold;
            s.pitchHold = s.hold & s.pitchEgHold;
        }

        // the voice
        if ((flags & 1) != 0) {
            s.sustainLevelValue = slTable[s.sustainLevel & 0xf];
            s.tl = tlTable[s.totalLevel & 0x3f];
            if (s.lpfOn == 1) s.lpf.setVoice(s.lpfVoice);
            if (s.noise == 0) {
                switch (s.format) {
                case 2 -> s.osc = Osc.PCM8B;
                case 3 -> s.osc = Osc.PCM8A;
                case 0 -> {
                    s.adpcmPosition = 0;
                    s.adpcmLast = 0;
                    s.adpcmStep = 0x7f;
                    s.osc = Osc.ADPCM;
                }
                default -> s.osc = Osc.PCM16;
                }
            } else {
                s.osc = Osc.NOISE;
            }
        }
        if (s.lpfOn == 1) {
            s.lpf.setHold1(special ? 0xfe : c != null ? c.hold1 : 0);
            s.lpf.setResonance(special ? -2 : c != null ? c.resonance : 0x20);
            s.lpf.setBrightness(special ? -2 : c != null ? c.brightness : 0x40);
            s.lpf.setFcOffset(s.fcOffset);
        }

        // key on, key off
        int keyState = 0;
        if ((flags & 6) != 0) {
            keyState = s.keyOn;
            boolean killed = false;
            if (keyState == 0) {
                if ((s.flags & 6) == 6) {
                    keyState = 2;
                    if (s.damp == 1) killed = true;
                } else if (s.damp == 1) {
                    killed = true;
                } else {
                    keyOff(s);
                }
            } else if (s.damp == 1) {
                killed = true;
            }
            if (killed) {
                s.eg = Eg.NONE;
                s.level = 0;
                s.damp = 0;
                s.lpf.setEgMode(0);
                if (keyState == 0) keyOff(s);
            }
            if (keyState == 1 || keyState == 2) {
                s.eg = Eg.KEYON;
                s.pitchEgState = 2;
                s.ended = false;
                s.lpf.setEgMode(2);
            }
        }

        // the pitch
        if ((flags & 9) != 0) {
            int block = s.block - s.blockOffset;
            s.keyCode = s.keyScale != 0 ? (s.fnum >>> 9) + block * 2 : 0;
            s.tlScaled = s.tl;
            if (s.levelKeyScale != 0) {
                s.tlScaled = (s.tl * kso[(s.levelKeyScale & 3) * 0x80 + ((((s.fnum >>> 7) & 7) + (block + 7) * 8) & 0x7f)]) >>> 15;
            }
            int step = 0;
            if (s.block != 0 || s.fnum != 0) {
                int r = chip.fs != 0 ? Integer.divideUnsigned(0x5dc00000, chip.fs) : 0;
                long p = ((((long) ((r * s.pitch) >>> 15) & 0xffffffffL) * (s.bend & 0xffffffffL)) >>> 16) & 0xffffffffL;
                p *= s.exBend2 & 0xffffffffL;
                step = (int) (p >> 16);
                if (s.exPitch == 1) {
                    step = (int) ((((p >> 16) & 0xffffffffL) * (s.exBend & 0xffffffffL)) >>> 16);
                }
            }
            s.step = step;
        }

        // the rates
        if ((flags & 0x49) != 0) {
            s.attackRate = arTable[rateIndex(s, s.attack)];
            s.decayRate = drTable[rateIndex(s, s.decay)];
            s.sustainRateValue = drTable[rateIndex(s, s.sustainRate)];
            s.releaseRate = drTable[rateIndex(s, s.release)];
            s.pitchEgRate0 = pitchRateTable[s.pitchRate0];
            s.pitchEgRate1 = pitchRateTable[s.pitchRate1];
            s.pitchEgRate2 = pitchRateTable[s.pitchRate2];
            s.pitchEgRate3 = pitchRateTable[s.pitchRate3];
            s.pitchEgLevel0 = pitchLevelTable[s.pitchLevel0];
            s.pitchEgLevel1 = pitchLevelTable[s.pitchLevel1];
            s.pitchEgLevel2 = pitchLevelTable[s.pitchLevel2];
            s.pitchEgLevel3 = pitchLevelTable[s.pitchLevel3];
            s.pitchEgLevel4 = pitchLevelTable[s.pitchLevel4];
        }

        // the lfo depths
        if ((flags & 0x11) != 0) {
            int m = s.modulation;
            if ((m & 0x7f) == 0) {
                s.vibratoOn2 = 0;
            } else {
                s.vibratoOn2 = s.vibratoOn;
                int v = ((m & 0x7f) * s.vibratoScale) & 0xff;
                if ((byte) m < 0) {
                    s.vibratoDepth = Math.min(v, 0x1f);
                } else {
                    s.vibratoDepth = Math.min(v, 8);
                }
            }
            if (s.tremolo == 0) {
                s.tremoloDepth = 0;
            } else {
                int v = (s.tremolo + s.tremoloScale - 1) & 0xff;
                s.tremoloDepth = Math.min(v, 3);
            }
        }
        s.flags = keyState == 2 ? 4 : 0;

        // the lfos
        if (s.eg != Eg.NONE) {
            int lfoIndex = s.lfoPhase >>> 20;
            s.lfoPhase += n * lfoRateTable[s.lfoRate & 3];
            if (s.vibratoOn2 == 0 || s.vibratoDepth == 0) {
                s.vibrato = s.step;
            } else {
                s.vibrato = (int) (((s.step & 0xffffffffL) * (vibratoTable[((s.vibratoDepth - 1) & 0x1f) * 0x1000 + lfoIndex] & 0xffffffffL)) >>> 20);
            }
            if (s.tremoloOn == 0 || s.tremolo == 0) {
                s.amplitude = s.tlScaled;
            } else {
                s.amplitude = (int) (((s.tlScaled & 0xffffffffL) * (tremoloTable[lfoIndex + s.tremoloDepth * 0x1000] & 0xffffffffL)) >> 15);
            }
        }

        // the samples
        int velocity = s.velocity;
        int sendBLevel = velocity * s.sendB >> 15;
        int sendALevel = velocity * s.sendA >> 15;
        int dryLevel2 = velocity * s.dry >> 15;
        for (int i = 0; i < n; i++) {
            int v = osc(s);
            if (s.lpfOn == 1) v = s.lpf.generate(v);
            v = v * volumes[i] >> 15;
            dryBuffer[i] = dryLevel2 * v >> 15;
            if (sendALevel != 0) sendABuffer[i] = sendALevel * v >> 15;
            if (sendBLevel != 0) sendBBuffer[i] = sendBLevel * v >> 15;
        }

        // the mix
        int sh = shift;
        if (s.dry != 0) {
            if (s.mute != 0) return;
            if (s.route == 0) {
                int[] l = bus[0], r = bus[1];
                for (int i = 0; i < n; i++) {
                    l[offset + i] += dryBuffer[i] * panLeft[i] >> (15 - sh);
                    r[offset + i] += dryBuffer[i] * panRight[i] >> (15 - sh);
                }
            } else if (s.route == 0x80) {
                int[] o = bus[5 + s.out];
                for (int i = 0; i < n; i++) o[offset + i] += dryBuffer[i] << sh;
            }
        }
        if (s.sendA != 0) {
            if (s.mute != 0) return;
            int[] o = bus[2];
            for (int i = 0; i < n; i++) o[offset + i] += sendABuffer[i] << sh;
        }
        if (s.sendB != 0 && s.mute == 0) {
            int[] l = bus[3], r = bus[4];
            for (int i = 0; i < n; i++) {
                l[offset + i] += sendBBuffer[i] * panLeft[i] >> (15 - sh);
                r[offset + i] += sendBBuffer[i] * panRight[i] >> (15 - sh);
            }
        }
    }

    /** the key off of a slot */
    private void keyOff(Slot s) {
        s.lpf.setEgMode(1);
        if (s.eg != Eg.NONE && s.egFree == 0) s.eg = Eg.RELEASE;
        if (s.pitchEgState != 0 && s.pitchEgFree == 0) s.pitchEgState = 1;
    }

    /** the index of a rate of 5 bits with the key scale */
    private static int rateIndex(Slot s, int rate) {
        if (rate == 0) return 0;
        if (rate == 0x1f) return 63;
        int r = s.keyCode + rate * 2;
        if (r < 0) return 0;
        return Math.min(r, 0x3f);
    }

    // ---- the oscillators

    /** the envelope a sample */
    private void envelope(Slot s) {
        switch (s.eg) {
        case KEYON -> {
            s.phase = 0;
            s.eg = Eg.ATTACK;
        }
        case ATTACK -> {
            int v = s.attackRate + s.level;
            if (v >= 0) {
                s.level = v;
            } else {
                s.level = 0x80000000;
                s.eg = Eg.DECAY;
            }
        }
        case DECAY -> {
            int v = (int) (((s.level & 0xffffffffL) * (s.decayRate & 0xffffffffL)) >>> 30);
            s.level = v;
            if (Integer.compareUnsigned(v, s.sustainLevelValue) <= 0) {
                s.eg = v == 0 ? Eg.NONE : Eg.SUSTAIN;
            }
        }
        case SUSTAIN -> {
            int v = (int) (((s.level & 0xffffffffL) * (s.sustainRateValue & 0xffffffffL)) >>> 30);
            s.level = v;
            if (v == 0) s.eg = Eg.NONE;
        }
        case RELEASE -> {
            int rate = s.holdRelease == 0 ? s.releaseRate : s.sustainRateValue;
            int v = (int) (((s.level & 0xffffffffL) * (rate & 0xffffffffL)) >>> 30);
            s.level = v;
            if (v == 0) s.eg = Eg.NONE;
        }
        default -> {}
        }
    }

    /** the sample of a slot, the envelope applied (oscSlot_*) */
    private int osc(Slot s) {
        if (s.eg == Eg.NONE) return 0;
        envelope(s);
        int amplitude = (s.amplitude * (s.level >>> 16)) >> 15;
        if (s.osc == Osc.NOISE) {
            if (s.amplitude == 0) return 0;
            int a = s.amplitude * (s.level >>> 16);
            return (a >> 15) * (short) noise.generate() >> 15;
        }
        if (s.ended) {
            return amplitude * s.last >> 15;
        }
        int phase = s.phase;
        int end = s.end;
        int i0 = Math.min(phase >>> 16, end);
        int i1 = i0 + 1;
        if (Integer.compareUnsigned(end, i1) <= 0) {
            i1 = Integer.compareUnsigned(end, s.loopStart) <= 0 ? i0 : s.loopStart;
        }
        int v = 0;
        if (s.amplitude != 0) {
            int frac = phase & 0xffff;
            int a, b;
            switch (s.osc) {
            case PCM8A -> {
                a = (byte) chip.memory[s.wave + i0] << 8;
                b = (byte) chip.memory[s.wave + i1] << 8;
            }
            case PCM8B -> {
                a = ((chip.memory[s.wave + i0] & 0xff) - 0x80) << 8;
                b = ((chip.memory[s.wave + i1] & 0xff) - 0x80) << 8;
            }
            case PCM16 -> {
                a = (short) ((chip.memory[s.wave + i0 * 2] & 0xff) | (chip.memory[s.wave + i0 * 2 + 1] & 0xff) << 8);
                b = (short) ((chip.memory[s.wave + i1 * 2] & 0xff) | (chip.memory[s.wave + i1 * 2 + 1] & 0xff) << 8);
            }
            default -> {
                a = adpcm(s, i0);
                b = adpcm(s, i1);
            }
            }
            v = amplitude * (a + (int) (((long) (b - a) * frac) >> 16)) >> 15;
        }
        // the pitch envelope and the lfo
        int p;
        if (s.pitchEgOn == 0) {
            p = phase + s.vibrato;
        } else {
            int index = pitchEnvelope(s);
            p = phase + (int) (((s.vibrato & 0xffffffffL) * (long) lfoPitchTable[index]) >> 29);
        }
        s.phase = p;
        int endPhase = end << 16;
        if (Integer.compareUnsigned(endPhase, p) <= 0) {
            if (Integer.compareUnsigned(s.loopStart, end) < 0) {
                int back = (s.loopStart << 16) - (end << 16);
                do {
                    p += back;
                } while (Integer.compareUnsigned(endPhase, p) <= 0);
                s.phase = p;
            } else {
                s.eg = Eg.RELEASE;
                s.ended = true;
            }
        }
        s.last = v;
        return v;
    }

    /** a sample of the adpcm, decoded up to it (oscSlot_4) */
    private int adpcm(Slot s, int index) {
        if (Integer.compareUnsigned(index, s.adpcmPosition) < 0) {
            return s.adpcm[index & 0x3fff];
        }
        int last = s.adpcmLast;
        int position = s.adpcmPosition;
        do {
            int b = chip.memory[s.wave + (position >>> 1)] & 0xff;
            int nibble = (position & 1) == 0 ? b & 0xf : b >> 4;
            int step = s.adpcmStep;
            int sign = nibble >> 3;
            last = last + ((((nibble >> 1) & 1) * (step >> 1) + ((nibble >> 2) & 1) * step + (nibble & 1) * (step >> 2) + (step >> 3)) ^ -sign) + sign;
            s.adpcmLast = last;
            int next = (step * adpcmCoef[nibble & 7]) >>> 14;
            short v;
            if (Integer.compareUnsigned(next, 0x7f) < 0) {
                s.adpcmStep = 0x7f;
            } else {
                s.adpcmStep = Integer.compareUnsigned(next, 0x6000) > 0 ? 0x6000 : next;
            }
            if (last > 0x7fff) {
                last = 0x7fff;
                s.adpcmLast = last;
            } else if (last < -0x8000) {
                last = -0x8000;
                s.adpcmLast = last;
            }
            v = (short) last;
            s.adpcm[position & 0x3fff] = v;
            position++;
            s.adpcmPosition = position;
        } while (position != index + 1);
        return last;
    }

    /** the pitch envelope a sample, @return the index of the pitch of the lfo table */
    private int pitchEnvelope(Slot s) {
        int l = s.pitchEgLevel;
        switch (s.pitchEgState) {
        case 1 -> {
            if (s.pitchHold == 0) {
                if (l < s.pitchEgLevel4) {
                    l += s.pitchEgRate3;
                    s.pitchEgLevel = l;
                } else if (s.pitchEgLevel4 < l) {
                    l -= s.pitchEgRate3;
                    s.pitchEgLevel = l;
                }
                return ((l >> 16) + 0x800) & 0xfff;
            } else {
                return ((l >> 16) + 0x800) & 0xfff;
            }
        }
        case 2 -> {
            s.pitchEgLevel = s.pitchEgLevel0;
            s.pitchEgState = 3;
            return ((s.pitchEgLevel0 >> 16) + 0x800) & 0xfff;
        }
        case 3 -> {
            int t = s.pitchEgLevel1;
            if (l < t) {
                l += s.pitchEgRate0;
                if (l < t) {
                    s.pitchEgLevel = l;
                    return ((l >> 16) + 0x800) & 0xfff;
                }
            } else {
                if (l <= t) {
                    s.pitchEgState = 4;
                    return ((l >> 16) + 0x800) & 0xfff;
                }
                l -= s.pitchEgRate0;
                if (t < l) {
                    s.pitchEgLevel = l;
                    return ((l >> 16) + 0x800) & 0xfff;
                }
            }
            s.pitchEgLevel = t;
            s.pitchEgState = 4;
            return ((t >> 16) + 0x800) & 0xfff;
        }
        case 4 -> {
            int t = s.pitchEgLevel2;
            if (l < t) {
                l += s.pitchEgRate1;
                if (l < t) {
                    s.pitchEgLevel = l;
                    return ((l >> 16) + 0x800) & 0xfff;
                }
                s.pitchEgLevel = t;
                s.pitchEgState = 5;
                return ((t >> 16) + 0x800) & 0xfff;
            } else {
                if (t < l) {
                    l -= s.pitchEgRate1;
                    if (l <= t) {
                        s.pitchEgLevel = t;
                        s.pitchEgState = 5;
                        return ((t >> 16) + 0x800) & 0xfff;
                    }
                    s.pitchEgLevel = l;
                    return ((l >> 16) + 0x800) & 0xfff;
                }
                s.pitchEgState = 5;
                return ((l >> 16) + 0x800) & 0xfff;
            }
        }
        case 5 -> {
            int t = s.pitchEgLevel3;
            if (l < t) {
                l += s.pitchEgRate2;
                s.pitchEgLevel = l;
            } else if (t < l) {
                l -= s.pitchEgRate2;
                s.pitchEgLevel = l;
            }
            return ((l >> 16) + 0x800) & 0xfff;
        }
        default -> {
            s.pitchEgLevel = 0;
            return 0x800;
        }
        }
    }
}
