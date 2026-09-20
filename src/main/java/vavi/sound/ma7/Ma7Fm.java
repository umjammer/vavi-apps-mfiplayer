/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import vavi.sound.ma7.Ma7Chip.Channel;
import vavi.sound.ma7.Ma7Chip.ExChannel;

import static java.lang.System.getLogger;


/**
 * The fm slots of the MA-7 (ARM::CFmSynth, ARM::FMCONTROL), 32 of them, of 2 or 4 operators.
 * <p>
 * A slot's voice is in the wave memory as the one of a wave table slot ({@link Ma7Wt}): 2 bytes,
 * 10 bytes an operator, and the filter ({@link Ma7Lpf}), which takes the output of an algorithm
 * when it is on.
 * <pre>
 * algorithm  operators
 * 0          1(fb) → 2
 * 1          1(fb) + 2
 * 2          1(fb) + 2 + 3(fb) + 4
 * 3          (1(fb) + (2 → 3)) → 4, 4 at the half
 * 4          1(fb) → 2 → 3 → 4
 * 5          (1(fb) → 2) + (3(fb) → 4)
 * 6          1(fb) + (2 → 3 → 4)
 * 7          1(fb) + (2 → 3) + 4
 * </pre>
 * an operator: a wave of 1024 (32 of them), an envelope of 4 rates, the key scaling of the level
 * and the rates, the multiple and the detune, a fixed pitch, the lfos.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Fm {

    private static final Logger logger = getLogger(Ma7Fm.class.getName());

    /** the stages of the envelope of an operator (the function pointer at +0x20) */
    enum Eg { NONE, KEYON, ATTACK, DECAY, SUSTAIN, RELEASE }

    static final int SLOTS = 32;

    /** an operator (ARM::_tagOperator, 0x68 bytes) */
    static final class Op {
        // the voice
        int noRelease;     // +0x00
        int fixed;         // +0x01
        int releaseHold;   // +0x02
        int ksr;           // +0x03
        int ar, dr, sr, rr;// +0x04 ~ +0x07
        int sl;            // +0x08
        int tl;            // +0x09
        int ksl;           // +0x0a
        int tremoloScale;  // +0x0b
        int tremoloOn;     // +0x0c
        int vibratoScale;  // +0x0d
        int vibratoOn;     // +0x0e
        int multi;         // +0x0f
        int dt;            // +0x10
        int wave;          // +0x11
        int fb;            // +0x12
        int fixedBlock;    // +0x13
        int fixedFnum;     // +0x14

        int keyCode;       // +0x18
        Eg eg = Eg.NONE;   // +0x20
        short[] waveTable; // +0x28
        int step;          // +0x30
        int phase;         // +0x34
        int tlValue;       // +0x38
        int kslValue;      // +0x3c
        int level;         // +0x40
        int attackRate;    // +0x44
        int decayRate;     // +0x48
        int sustainRate;   // +0x4c
        int releaseRate;   // +0x50
        int egLevel;       // +0x54
        int slValue;       // +0x58
        int vibratoOn2;    // +0x5c
        int vibratoDepth;  // +0x5d
        int tremoloDepth;  // +0x5e
        int holdRelease;   // +0x5f
        int amplitude;     // +0x60
        int vibrato;       // +0x64, the step with the vibrato
    }

    /** a slot (ARM::_tagFMSLOTINFO, 0x280 bytes at +0x1808) */
    static final class Slot {
        boolean active;          // +0x1808
        int flags;               // +0x180c
        int state;               // +0x1810
        int pan;                 // +0x1814
        int panOff;              // +0x1815
        int mono;                // +0x1816
        int lfoRate;             // +0x1817
        int algorithm;           // +0x1818
        int lpfOn;               // +0x1819
        int blockOffset;         // +0x181c
        final Op[] ops = { new Op(), new Op(), new Op(), new Op() }; // +0x1820, +0x1888, +0x18f0, +0x1958
        Ma7Lpf.Voice lpfVoice;   // +0x19c0
        int opCount;             // +0x19e4
        int keyOn;               // +0x19f0
        int block;               // +0x19f4
        int fnum;                // +0x19f8
        int bend = 0x10000;      // +0x19fc
        int exBend = 0x10000;    // +0x1a00
        int exBend2 = 0x10000;   // +0x1a04
        int modulation;          // +0x1a08
        int tremolo;             // +0x1a09
        int hold;                // +0x1a0a
        int volumeAsIs;          // +0x1a0c
        int fb0;                 // +0x1a10
        int fb2;                 // +0x1a14
        int history0, history1;  // +0x1a18, +0x1a1c
        int history2, history3;  // +0x1a20, +0x1a24
        int step;                // +0x1a28
        int lfoPhase;            // +0x1a30
        int velocityRaw;         // +0x1a3c
        int velocity;            // +0x1a40
        int mute;                // +0x1a44
        int damp;                // +0x1a45
        int channel;             // +0x1a46
        int exPitch;             // +0x1a47
        int fcOffset;            // +0x1a50
        int route;               // +0x1a68
        int out;                 // +0x1a69
        int dry;                 // +0x1a78
        int sendA;               // +0x1a7c
        int sendB;               // +0x1a80

        Ma7Lpf lpf;              // +0x1a48
        Ma7Interpolators.Volume volumeIp; // +0x1a58
        Ma7Interpolators.Pan panIp;       // +0x1a60
    }

    final Slot[] slots = new Slot[SLOTS];

    private final Ma7Chip chip;

    /** the level shift, 0 ~ 3 (+0x6808) */
    private int shift;
    /** the level, 15 bit (+0x680c) */
    private int volume = 0x8000;

    // tables
    private final int[] blockOffsetTable, arTable, drTable, lfoRateTable;
    private final int[] slTable, tlTable, feedbackTable, mask, multiTable, dtTable;
    private final byte[] dtt;
    private final int[] klTable;
    private final int[] levelTable, dbTable;
    private final int[] vibratoTable, tremoloTable;
    private final short[][] waves = new short[32][];

    // the buffers of a block (statics of the library)
    private final int[] volumes = new int[32];
    private final int[] panLeft = new int[32], panRight = new int[32];
    private final int[] dryBuffer = new int[32], sendABuffer = new int[32], sendBBuffer = new int[32];

    Ma7Fm(Ma7Chip chip) {
        this.chip = chip;
        Ma7Rom rom = chip.rom;
        if (chip.fs != 48000) throw new IllegalArgumentException("only 48 kHz: " + chip.fs);
        blockOffsetTable = rom.ints(0x232680, 4);
        arTable = rom.ints(0x2c0110, 64);
        drTable = rom.ints(0x2c0210, 64);
        lfoRateTable = rom.ints(0x2c0310, 4);
        slTable = rom.ints(0x2cf480, 16);
        tlTable = rom.ints(0x2cf380, 64);
        feedbackTable = rom.ints(0x476fa0, 8);
        mask = rom.ints(0x2cf4c0, 8);
        multiTable = rom.ints(0x2ced30, 16);
        dtTable = rom.ints(0x2ced70, 4 * 0x20);
        dtt = rom.bytes(0x2cef70, 16);
        klTable = new int[4 * 0x80];
        for (int i = 0; i < klTable.length; i++) klTable[i] = rom.u16(0x2cef80 + i * 2);
        levelTable = rom.ints(0x2326b0, 0x404);
        dbTable = rom.ints(0x2336c0, 32);
        vibratoTable = rom.ints(0x2337c0, 31 * 0x1000);
        tremoloTable = rom.ints(0x2af7c0, 4 * 0x1000);
        for (int i = 0; i < 32; i++) {
            if (i == 15 || i == 23 || i == 31) {
                waves[i] = new short[0x400]; // the waves of the ram (SetWave)
            } else {
                int p = rom.pointer(0x476ea0 + i * 8);
                waves[i] = new short[0x400];
                for (int j = 0; j < 0x400; j++) waves[i][j] = (short) rom.s16(p + j * 2);
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            Slot s = new Slot();
            s.lpf = new Ma7Lpf(rom, chip.noise, chip.fs);
            s.volumeIp = new Ma7Interpolators.Volume(rom, chip.fs);
            s.panIp = new Ma7Interpolators.Pan(rom, chip.fs);
            slots[i] = s;
        }
    }

    /** FMCONTROL_SetVolume */
    void setVolume(int shift, int volume) {
        if (Integer.compareUnsigned(volume, 0x7fff) > 0) volume = 0x7fff;
        this.shift = shift & 3;
        this.volume = volume;
    }

    /** FMCONTROL_SetFMWaveReg, the waves of the ram */
    void setWaveRegister(int address, int data) {
        chip.regC[address] = data & 0x7f;
        if ((address - 0x29) % 3 == 2) {
            int a = chip.regC[address - 2] << 15 | chip.regC[address - 1] << 8 | (data & 0x7f) << 1;
            if (a - 0x10000 >= 0 && a - 0x10000 <= 0x37ff) {
logger.log(Level.WARNING, "SetWave is not of this: %d, 0x%x".formatted(new int[] { 15, 23, 31 }[(address - 0x29) / 3], a));
            }
        }
    }

    /** FMCONTROL_SetFMVoiceReg */
    void setVoiceRegister(int slot, int address, int data, int reset) {
        if (slot >= 0x40) return;
        int[] info = chip.fmInfo[slot];
        switch (address) {
        case 1 -> info[0] = data & 3;
        case 2 -> info[1] = data & 0x7f;
        case 3 -> {
            info[2] = data & 0x7f;
            if (reset == 0) {
                int a = info[1] << 8 | info[0] << 15 | (data & 0x7f) << 1;
                if (a <= 0x13fff) loadVoice(slot, a);
            }
        }
        case 4 -> info[3] = data & 0x3f;
        case 5 -> {
            info[4] = data & 0x7f;
            if (slot < SLOTS) slots[slot].fcOffset = ((data & 0x7f) | (info[3] << 7)) & 0x1fff;
        }
        case 6 -> {
            info[5] = data & 0x7d;
            if (slot < SLOTS) {
                Slot s = slots[slot];
                int v = ((data & 0x7d) >> 2) & 0x1f;
                s.velocityRaw = v;
                s.velocity = s.volumeIp.linear(v);
                s.volumeAsIs = data & 1;
            }
        }
        case 7 -> info[6] = data & 0x3f;
        case 8 -> {
            info[7] = data & 0x7f;
            if (slot < SLOTS) {
                Slot s = slots[slot];
                s.block = (info[6] >> 3) & 7;
                s.fnum = (data & 0x7f) | (info[6] & 7) << 7;
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
            if ((chip.regM[0][2] & 0x80) != 0 || slot < SLOTS) {
                keyOn(slot, (data >> 6) & 1, (data >> 4) & 1, (data >> 5) & 1);
            }
            info[9] = data & 0x7f;
        }
        default -> {}
        }
    }

    /** an operator of 10 bytes (0x4631c) */
    private void parseOp(Op o, int a) {
        int q0 = chip.memory(a), q1 = chip.memory(a + 1), q2 = chip.memory(a + 2), q3 = chip.memory(a + 3), q4 = chip.memory(a + 4);
        int q5 = chip.memory(a + 5), q6 = chip.memory(a + 6), q7 = chip.memory(a + 7), q8 = chip.memory(a + 8), q9 = chip.memory(a + 9);
        o.noRelease = (q0 >> 3) & 1;
        o.fixed = (q0 >> 2) & 1;
        o.releaseHold = (q0 >> 1) & 1;
        o.ksr = q0 & 1;
        o.sr = (q0 >> 4) == 0 ? 0 : ((q5 >> 1) & 1) | ((q0 >> 3) & 0x1e);
        o.rr = (q1 >> 4) == 0 ? 0 : ((q1 >> 3) & 0x1e) | (q5 & 1);
        o.dr = (q1 & 0xf) == 0 ? 0 : ((q5 >> 2) & 1) | ((q1 & 0xf) << 1);
        o.ar = (q2 >> 4) == 0 ? 0 : ((q5 >> 3) & 1) | ((q2 >> 3) & 0x1e);
        o.sl = q2 & 0xf;
        o.tl = q3 >> 2;
        o.ksl = new int[] { 0, 2, 1, 3 }[q3 & 3];
        o.tremoloScale = (q4 >> 5) & 3;
        o.tremoloOn = (q4 >> 4) & 1;
        o.vibratoScale = new int[] { 1, 2, 4, 8 }[(q4 >> 1) & 3];
        o.vibratoOn = q4 & 1;
        o.wave = q6 >> 3;
        o.fb = q6 & 7;
        o.fixedBlock = (q7 >> 2) & 7;
        o.fixedFnum = (q7 & 3) << 8 | q8;
        o.multi = (q9 >> 4) & 0xf;
        o.dt = q9 & 0xf;
    }

    /** the voice, of the memory, to a slot (FMCONTROL_SetFMVoiceReg 3, CFmSynth::SetVoice) */
    private void loadVoice(int slot, int a) {
        if (slot >= SLOTS) return;
        Slot s = slots[slot];
        int p0 = chip.memory(a), p1 = chip.memory(a + 1);
        s.pan = p0 >> 3;
        s.panOff = (p1 >> 5) & 1;
        s.mono = (p1 >> 4) & 1;
        s.lfoRate = p1 >> 6;
        s.algorithm = p1 & 7;
        s.lpfOn = (p1 >> 3) & 1;
        s.blockOffset = blockOffsetTable[p0 & 3];
        s.opCount = s.algorithm < 2 ? 2 : 4;
        parseOp(s.ops[0], a + 2);
        parseOp(s.ops[1], a + 0xc);
        s.ops[1].fb = 0;
        int f;
        if (s.opCount == 4) {
            parseOp(s.ops[2], a + 0x16);
            parseOp(s.ops[3], a + 0x20);
            s.ops[3].fb = 0;
            f = a + 0x20 + 0xa;
        } else {
            s.ops[2].eg = Eg.NONE;
            s.ops[3].eg = Eg.NONE;
            f = a + 0xc + 0xa;
        }
        int[] q = new int[0x10];
        for (int i = 0; i < q.length; i++) q[i] = chip.memory(f + i);
        s.lpfVoice = new Ma7Lpf.Voice(q[0xc] >> 7, q[0xd] >> 7, q[0xe] >> 7, q[0xf] >> 7, q[0] & 0x1f,
                ((q[2] & 0x1f) << 8 | q[3]) & 0x1fff, ((q[4] & 0x1f) << 8 | q[5]) & 0x1fff,
                ((q[6] & 0x1f) << 8 | q[7]) & 0x1fff, ((q[8] & 0x1f) << 8 | q[9]) & 0x1fff,
                ((q[10] & 0x1f) << 8 | q[11]) & 0x1fff,
                q[0xc] & 0x1f, q[0xd] & 0x1f, q[0xe] & 0x1f, q[0xf] & 0x1f,
                q[1] >> 5, (q[1] >> 4) & 1, q[1] & 7, (q[1] >> 3) & 1);
        s.active = true;
        s.flags |= 1;
    }

    /** CFmSynth::KeyOn */
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
        } else if (s.keyOn == 1) {
            s.keyOn = 0;
            s.flags |= 4;
        }
    }

    /** the level of 5 bits (0x2326b0, 0x2336c0) */
    private int level(int v) {
        return levelTable[dbTable[v & 0x1f] >>> 16];
    }

    private static boolean special(int ch) {
        return (ch >= 0x40 && ch < 0x80) || ch > 0x8c;
    }

    /** CFmSynth::Generate */
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

    private void generate(Slot s, int n, int offset, int[][] bus) {
        if (s.ops[0].eg == Eg.NONE && s.ops[1].eg == Eg.NONE && s.ops[2].eg == Eg.NONE && s.ops[3].eg == Eg.NONE && s.lpf.isDead()) {
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
        int keyOnFlag = s.volumeAsIs == 0 ? (s.flags >> 1) & 1 : 0;
        s.volumeIp.generate(volume1, volume2, false, keyOnFlag, volumes, n);
        s.panIp.generate(s.pan, pan1, pan2, s.panOff != 0, keyOnFlag, s.mono != 0, panLeft, panRight, n);

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
        if ((flags & 0x41) != 0) {
            for (int k = 0; k < s.opCount; k++) s.ops[k].holdRelease = s.hold & s.ops[k].releaseHold;
        }

        // the voice
        if ((flags & 1) != 0) {
            s.fb0 = feedbackTable[s.ops[0].fb & 7];
            if (s.opCount == 4) s.fb2 = feedbackTable[s.ops[2].fb & 7];
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                o.waveTable = waves[o.wave & 0x1f];
                o.slValue = slTable[o.sl & 0x1f];
                o.tlValue = tlTable[o.tl & 0x3f];
            }
            if (s.lpfOn == 1) s.lpf.setVoice(s.lpfVoice);
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
            if ((s.flags & 6) == 6 && keyState == 0) {
                keyState = 2;
            }
            if (keyState == 1 || keyState == 2) {
                s.history0 = 0;
                s.history1 = 0;
                s.history2 = 0;
                s.history3 = 0;
            }
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                if (s.damp == 1) {
                    o.eg = Eg.NONE;
                    o.egLevel = 0;
                }
                if (keyState == 1 || keyState == 2) {
                    o.eg = Eg.KEYON;
                } else if (keyState == 0) {
                    if (o.eg != Eg.NONE && o.noRelease == 0) o.eg = Eg.RELEASE;
                }
            }
            if (s.opCount != 4) {
                s.ops[2].eg = Eg.NONE;
                s.ops[3].eg = Eg.NONE;
            }
            if (s.damp == 1) {
                s.lpf.setEgMode(0);
                s.damp = 0;
            }
            if (keyState == 0) {
                s.lpf.setEgMode(1);
            } else if (keyState == 1 || keyState == 2) {
                s.lpf.setEgMode(2);
            }
        }

        // the pitch
        if ((flags & 9) != 0) {
            int blk = clamp(s.blockOffset + s.block, 0, 7);
            int kc7 = blk | (s.fnum >>> 6) << 3;
            int kc = s.fnum >>> 9 | blk << 1;
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                o.keyCode = kc;
                int ksl = kc7;
                if (o.fixed != 0) {
                    int b = clamp(s.blockOffset + o.fixedBlock, 0, 7);
                    o.keyCode = (o.fixedFnum >>> 9 | b << 1) & 0xff;
                    ksl = b | (o.fixedFnum >>> 6) << 3;
                }
                if (o.ksl == 0) {
                    o.level = o.tlValue;
                    o.kslValue = 0x8000;
                } else {
                    int v = klTable[((o.ksl - 1) & 3) * 0x80 + (ksl & 0x7f)];
                    o.kslValue = v;
                    o.level = (v * o.tlValue) >> 15;
                }
            }
            int blk2 = s.blockOffset + s.block;
            int dtIndex;
            if (blk2 < 0) {
                blk2 = 0;
                dtIndex = 0;
            } else if (blk2 < 8) {
                dtIndex = (blk2 & 0x3f) << 2;
            } else {
                blk2 = 7;
                dtIndex = 0x1c;
            }
            int r = chip.fs != 0 ? Integer.divideUnsigned(0xbb80000, chip.fs) : 0;
            long p = ((((long) ((s.fnum << blk2) * r) & 0xffffffffL) * (long) s.bend) >> 16) & 0xffffffffL;
            p *= s.exBend2;
            int step = (int) (p >>> 16);
            if (s.exPitch == 1) {
                step = (int) ((((p >> 16) & 0xffffffffL) * (s.exBend & 0xffffffffL)) >>> 16);
            }
            s.step = step;
            dtIndex = (dtIndex + dtt[(s.fnum >>> 6) & 0xf]) & 0xff;
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                long inc;
                int dti;
                if (o.fixed == 0) {
                    inc = step & 0xffffffffL;
                    dti = dtIndex;
                } else {
                    int b = s.blockOffset + o.fixedBlock;
                    int bx;
                    if (b < 0) {
                        b = 0;
                        bx = 0;
                    } else if (b < 8) {
                        bx = (b & 0x3f) << 2;
                    } else {
                        b = 7;
                        bx = 0x1c;
                    }
                    dti = bx + dtt[(o.fixedFnum >>> 6) & 0xf];
                    long v = ((long) ((o.fixedFnum << b) * r) & 0xffffffffL) * (long) s.bend;
                    inc = (v >> 16) & 0xffffffffL;
                }
                if (o.dt != 0) {
                    int v = (int) (inc >>> 16) * chip.fs;
                    int d = dtTable[(o.dt & 3) * 0x20 + (dti & 0x1f)];
                    if (Integer.compareUnsigned(v, 0x10000) < 0) v = 0x10000;
                    long div = v & 0xffffffffL;
                    int m = switch (o.dt & 0xc) {
                        case 4 -> v - d;
                        case 8 -> v + d * 2;
                        case 0 -> v + d;
                        default -> v + d * -2;
                    };
                    inc = (inc * (m & 0xffffffffL)) / div;
                }
                o.step = (int) (((inc & 0xffffffffL) * (multiTable[o.multi] & 0xffffffffL)) >> 15);
            }
        }

        // the rates
        if ((flags & 0x49) != 0) {
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                int ks = o.ksr == 0 ? (o.keyCode >>> 2) & 0xff : o.keyCode & 0xff;
                o.attackRate = arTable[rateIndex(ks, o.ar)];
                o.decayRate = drTable[rateIndex(ks, o.dr)];
                o.sustainRate = drTable[rateIndex(ks, o.sr)];
                o.releaseRate = drTable[rateIndex(ks, o.rr)];
            }
        }

        // the lfo depths
        if ((flags & 0x11) != 0) {
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                int m = s.modulation;
                if ((m & 0x7f) == 0) {
                    o.vibratoOn2 = 0;
                } else {
                    o.vibratoOn2 = o.vibratoOn;
                    int v = ((m & 0x7f) * o.vibratoScale) & 0xff;
                    if ((byte) m < 0) {
                        o.vibratoDepth = v < 0x20 ? v : 0x1f;
                    } else {
                        o.vibratoDepth = v < 9 ? v : 8;
                    }
                }
                if (s.tremolo == 0) {
                    o.tremoloDepth = 1;
                } else {
                    int v = (s.tremolo + o.tremoloScale) & 0xff;
                    o.tremoloDepth = Math.min(v, 4);
                }
            }
        }
        s.flags = keyState == 2 ? 4 : 0;

        // the lfos
        if (s.opCount != 0) {
            int lfoIndex = s.lfoPhase >>> 20;
            s.lfoPhase += n * lfoRateTable[s.lfoRate];
            for (int k = 0; k < s.opCount; k++) {
                Op o = s.ops[k];
                if (o.vibratoOn2 == 0 || o.vibratoDepth == 0) {
                    o.vibrato = o.step;
                } else {
                    o.vibrato = (int) (((o.step & 0xffffffffL) * (vibratoTable[((o.vibratoDepth - 1) & 0x1f) * 0x1000 + lfoIndex] & 0xffffffffL)) >>> 20);
                }
                if (o.tremoloOn == 0 || o.tremoloDepth == 0) {
                    o.amplitude = o.level;
                } else {
                    o.amplitude = (int) (((o.level & 0xffffffffL) * (tremoloTable[((o.tremoloDepth - 1) << 12) + lfoIndex] & 0xffffffffL)) >>> 15);
                }
            }
        }

        algorithm(s, n);

        // the mix
        int sh = shift;
        if (s.dry != 0) {
            if (s.mute != 0) return;
            if (s.route == 0) {
                int[] l = bus[0], r = bus[1];
                for (int i = 0; i < n; i++) {
                    l[offset + i] += panLeft[i] * dryBuffer[i] >> (15 - sh);
                    r[offset + i] += panRight[i] * dryBuffer[i] >> (15 - sh);
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
                l[offset + i] += panLeft[i] * sendBBuffer[i] >> (15 - sh);
                r[offset + i] += panRight[i] * sendBBuffer[i] >> (15 - sh);
            }
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    /** the index of a rate of 5 bits with the key scale */
    private static int rateIndex(int ks, int r) {
        if (r == 0) return 0;
        if (r == 0x1f) return 63;
        if ((r & 0x7f) == 0) return 0;
        int v = (ks + (r & 0x7f) * 2) & 0xff;
        return Math.min(v, 0x3f);
    }

    // ---- the operators

    /** the envelope of an operator a sample (CalcEgSlot_*) */
    private static void envelope(Op o) {
        switch (o.eg) {
        case KEYON -> {
            o.phase = 0;
            o.eg = Eg.ATTACK;
        }
        case ATTACK -> {
            int v = o.attackRate + o.egLevel;
            if (v >= 0) {
                o.egLevel = v;
            } else {
                o.egLevel = 0x80000000;
                o.eg = Eg.DECAY;
            }
        }
        case DECAY -> {
            int v = (int) (((o.egLevel & 0xffffffffL) * (o.decayRate & 0xffffffffL)) >>> 30);
            o.egLevel = v;
            if (Integer.compareUnsigned(v, o.slValue) <= 0) {
                o.eg = v == 0 ? Eg.NONE : Eg.SUSTAIN;
            }
        }
        case SUSTAIN -> {
            int v = (int) (((o.egLevel & 0xffffffffL) * (o.sustainRate & 0xffffffffL)) >>> 30);
            o.egLevel = v;
            if (v == 0) o.eg = Eg.NONE;
        }
        case RELEASE -> {
            int rate = o.holdRelease == 0 ? o.releaseRate : o.sustainRate;
            int v = (int) (((o.egLevel & 0xffffffffL) * (rate & 0xffffffffL)) >>> 30);
            o.egLevel = v;
            if (v == 0) o.eg = Eg.NONE;
        }
        default -> {}
        }
    }

    /** an operator without modulation */
    private static int op(Op o) {
        if (o.eg == Eg.NONE) return 0;
        envelope(o);
        int v = 0;
        if (o.amplitude != 0) {
            v = ((o.amplitude * (o.egLevel >>> 16)) >> 15) * o.waveTable[o.phase >>> 22] >> 15;
        }
        o.phase += o.vibrato;
        return v;
    }

    /** an operator modulated */
    private int op(Op o, int in) {
        return op(o, in, 15);
    }

    /** an operator modulated, the level shifted by {@code shift} */
    private int op(Op o, int in, int shift) {
        if (o.eg == Eg.NONE) return 0;
        envelope(o);
        int v = 0;
        if (o.amplitude != 0) {
            v = ((o.amplitude * (o.egLevel >>> 16)) >> 15) * o.waveTable[((mask[o.fb] & (in >> 3)) + (o.phase >>> 22)) & 0x3ff] >> shift;
        }
        o.phase += o.vibrato;
        return v;
    }

    /** the feedback operators, @return the output */
    private int feedback(Op o, int fb, int h0, int h1) {
        if (fb == 0) return op(o);
        return op(o, (h0 + h1) >> fb);
    }

    /** CalcAlg0 ~ 7, CalcAlg0a ~ 7a */
    private void algorithm(Slot s, int n) {
        int vel = s.velocity;
        int dryLevel = (vel * s.dry) >>> 15;
        int sendALevel = (vel * s.sendA) >>> 15;
        int sendBLevel = (vel * s.sendB) >>> 15;
        Op o0 = s.ops[0], o1 = s.ops[1], o2 = s.ops[2], o3 = s.ops[3];
        int h0 = s.history0, h1 = s.history1, h2 = s.history2, h3 = s.history3;
        boolean lpf = s.lpfOn == 1;
        int outShift = switch (s.algorithm) {
            case 0, 4 -> 15;
            case 1, 3, 5, 6 -> 14;
            default -> 13;
        };
        for (int i = 0; i < n; i++) {
            int sum;
            switch (s.algorithm) {
            case 0 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                sum = op(o1, a);
            }
            case 1 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                int b = op(o1);
                sum = lpf ? a + b : a + b >> 1;
            }
            case 2 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                int b = op(o1);
                int c = feedback(o2, s.fb2, h2, h3);
                h3 = h2; h2 = c;
                int d = op(o3);
                sum = lpf ? a + b + c + d : a + b + c + d >> 2;
            }
            case 3 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                int b = op(o1);
                int c = op(o2, b);
                sum = op(o3, a + c, lpf ? 15 : 16);
            }
            case 4 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                sum = op(o3, op(o2, op(o1, a)));
            }
            case 5 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                int b = op(o1, a);
                int c = feedback(o2, s.fb2, h2, h3);
                h3 = h2; h2 = c;
                int d = op(o3, c);
                sum = lpf ? b + d : b + d >> 1;
            }
            case 6 -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                int d = op(o3, op(o2, op(o1)));
                sum = lpf ? a + d : a + d >> 1;
            }
            default -> {
                int a = feedback(o0, s.fb0, h0, h1);
                h1 = h0; h0 = a;
                int c = op(o2, op(o1));
                int d = op(o3);
                sum = lpf ? a + c + d : a + c + d >> 2;
            }
            }
            if (lpf) {
                long v = ((long) s.lpf.generate(sum) * volumes[i]) >> 15;
                dryBuffer[i] = (int) ((dryLevel & 0xffffffffL) * v >> 15);
                sendABuffer[i] = (int) ((sendALevel & 0xffffffffL) * v >> 15);
                sendBBuffer[i] = (int) ((sendBLevel & 0xffffffffL) * v >> 15);
            } else {
                int v = sum * volumes[i] >> 15;
                dryBuffer[i] = v * dryLevel >> outShift;
                sendABuffer[i] = v * sendALevel >> outShift;
                sendBBuffer[i] = v * sendBLevel >> outShift;
            }
        }
        s.history0 = s.fb0 == 0 ? 0 : h0;
        s.history1 = s.fb0 == 0 ? 0 : h1;
        if (s.opCount == 4 && (s.algorithm == 2 || s.algorithm == 5)) {
            s.history2 = s.fb2 == 0 ? 0 : h2;
            s.history3 = s.fb2 == 0 ? 0 : h3;
        }
    }
}
