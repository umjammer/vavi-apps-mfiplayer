/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import static java.lang.System.getLogger;


/**
 * The MA-7 as {@code libM7_EmuSmw7.so} emulates it ({@code Hw_*}, namespace {@code ARM}): the
 * ports the driver writes and reads, the registers behind them, and the rendering of the
 * voices into 1 ms blocks.
 * <p>
 * the ports
 * <pre>
 * 0      status, bit 7 is kept, bits 6 and 1 are cleared by writing 1
 * 1, 2   the index and the data of an intermediate register (the "IREG", {@link #setIntermediateRegister})
 * 3      packets: a 7 bit address (1 ~ 3 bytes, bit 7 ends it) and data bytes to the control registers
 *        ({@link #setControlRegister}, bit 7 ends the packet), or with an address of 3 bytes a count
 *        and bytes to the wave ram
 * 4, 5   the sequencers' fifos
 * 6 ~ 9  the streams' fifos
 * 10     a read request, a control register or a byte of the memory into the read latch
 * 11, 12 the irq status to clear, 13, 14 the irq enable
 * </pre>
 * the control registers
 * <pre>
 * 0x00          the slot: 0 ~ 63, 0x40 the wave table slots
 * 0x01 ~ 0x0a   the registers of the slot, {@link Ma7Fm}, {@link Ma7Wt}
 * 0x0b          the channel: 0 ~ 63, 0x40 resets it
 * 0x0c ~ 0x17   the parameters of the channel, {@link Channel}
 * 0x18 ~ 0x25   the software irqs, 0x26 the sequencers' stop
 * 0x29 ~ 0x31   the waves of the fm and the human voice, 0x32 ~ 0x5c the human voice
 * 0x5d ~ 0x71   the streams, 0x72 ~ 0x79 the ex channels, 0x7a ~ 0x7f the dsp
 * </pre>
 * The streams, the human voice, dtmf, din and the sequencers are of the chip too, but not of
 * what a midi synthesizer plays, and what they are told is kept and nothing more.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public final class Ma7Chip {

    private static final Logger logger = getLogger(Ma7Chip.class.getName());

    /** the rom */
    final Ma7Rom rom;

    /** the sampling rate */
    final int fs;

    /** samples of a block, 1 ms (0x4829a8) */
    final int blockSize;

    // ---- virtual registers (ARM::VIRTUALREGISTER)

    /** the intermediate registers, 2 banks of 256 (0x497ca0) */
    final int[][] regM = new int[2][256];

    /** the control registers, 128 (0x497ea0) */
    final int[] regC = new int[128];

    /** the registers of the fm slots, 64 * 10 (0x4973c0) */
    final int[][] fmInfo = new int[64][10];
    /** the registers of the wave table slots, 64 * 10 (0x497640) */
    final int[][] wtInfo = new int[64][10];
    /** the registers of the channels, 64 * 12 (0x497910) */
    final int[][] chInfo = new int[64][12];
    /** the registers of the ex channels, 16 * 7 (0x497c10) */
    final int[][] exInfo = new int[16][7];
    /** the registers of the streams, 4 * 20 (0x4978c0) */
    final int[][] streamInfo = new int[4][20];

    // ---- virtual memory (ARM::VIRTUALMEMORY)

    /** the wave rom, 64 KB, and the ram, 16 KB after it */
    final byte[] memory = new byte[0x14000];

    static final int ROM_SIZE = 0x10000;
    static final int MEMORY_SIZE = 0x14000;
    /** where the wave rom is in the library */
    static final int ROM = 0x17bf50;

    int memory(int address) {
        if (address < ROM_SIZE) return memory[address] & 0xff;
        if (address >= MEMORY_SIZE) return 0xff;
        return memory[address] & 0xff;
    }

    void setMemory(int address, int data) {
        if (address - ROM_SIZE >= 0 && address < MEMORY_SIZE) memory[address] = (byte) data;
    }

    // ---- the channels (ARM::CCh, ARM::gChCi, ARM::gChEi)

    /** a channel, what a voice sounds by (ARM::gChCi, 0x20 bytes) */
    static final class Channel {
        /** the ex channel */
        int exId;           // +0
        int exOn;           // +1
        int exMode;         // +2
        /** the lfo depth, bit 7: as it is */
        int modulation;     // +3
        int hold1;          // +4
        int tremolo;        // +5
        int sfx1;           // +6
        int sfx2;           // +7
        int dry;            // +8
        /** 5 bit, bit 31: interpolated */
        int volume;         // +0xc
        /** 5 bit, bit 31: interpolated */
        int pan;            // +0x10
        int resonance;      // +0x14
        int brightness;     // +0x18
        /** 0x10000 is none */
        int pitchBend;      // +0x1c

        void reset() {
            exId = 0;
            modulation = 0;
            hold1 = 0;
            tremolo = 0;
            sfx1 = 0;
            sfx2 = 0;
            dry = 0x1f;
            volume = 0x19;
            pan = 0x10;
            resonance = 0x20;
            brightness = 0x40;
            pitchBend = 0x10000;
        }
    }

    /** an ex channel (ARM::gChEi, 0x14 bytes) */
    static final class ExChannel {
        int mode;           // +0
        int out;            // +1
        int volume;         // +4
        int pan;            // +8
        int pitchBend;      // +0xc
        int pitchBend2;     // +0x10

        void reset() {
            mode = 0;
            out = 0;
            volume = 0x19;
            pan = 0x10;
            pitchBend = 0x10000;
            pitchBend2 = 0x10000;
        }
    }

    final Channel[] channels = new Channel[64];
    final ExChannel[] exChannels = new ExChannel[16];

    // ---- the units

    /** the noise all take (ARM::CWnoise::m_dRand) */
    final Ma7Noise noise = new Ma7Noise();

    final Ma7Fm fm;
    final Ma7Wt wt;
    final Ma7Dsp dsp;

    /** the 13 buses a block is mixed into (ARM::gOutBuf): 0, 1 dry, 2 ~ 4 the sends, 5 ~ 8 the outs, 9, 10 the dsp */
    final int[][] bus;

    // ---- the state of the chip (0x482970 ~)

    /** 0xff until initialized (0x468030) */
    private boolean initialized;
    /** the status, port 0 (0x482971) */
    int status;
    /** the intermediate register index, port 1 (0x482970) */
    int iregIndex;
    /** what a read request latched (0x4829f0) */
    int readLatch;
    /** the irq status (0x4829c0) */
    int irqStatus;
    /** the irq enable (0x4829e0) */
    int irqEnable;
    /** which irqs are there (0x468038) */
    int irqMask = 0xfff0;

    /** port 3 */
    private int packetState = 3, packetAddress, packetCount;
    /** port 10 */
    private int readState = 3, readAddress;

    /** samples rendered (0x4829b0) */
    long samples;
    /** ms rendered (0x4829b8) */
    long ms;

    /** din / rec timing of the intermediate registers 0x87 and 0x8d (0x4829c4 ~) */
    private int dinCount0 = 0, dinPeriod0 = 0x535, dinCount1 = 0, dinPeriod1 = 0x29aa;

    final Ma7Timer timer = new Ma7Timer();
    final Ma7IrqFifo irqFifo = new Ma7IrqFifo();

    /** the level of 5 bits (0x17be30) */
    final int[] volumeTable;

    public Ma7Chip(Ma7Rom rom, int fs) {
        this.rom = rom;
        this.fs = fs;
        this.blockSize = (fs + 999) / 1000;
        this.volumeTable = rom.ints(0x17be30, 32);
        this.bus = new int[13][blockSize];
        for (int i = 0; i < 64; i++) channels[i] = new Channel();
        for (int i = 0; i < 16; i++) exChannels[i] = new ExChannel();
        // Hw_Initialize
        // CCh::Initialize
        for (ExChannel e : exChannels) e.reset();
        for (Channel c : channels) c.reset();
        fm = new Ma7Fm(this);
        wt = new Ma7Wt(this);
        dsp = new Ma7Dsp(this);
        initialized = true;
        status = 0;
        // VIRTUALMEMORY_Initialize
        System.arraycopy(rom.bytes(ROM, ROM_SIZE), 0, memory, 0, ROM_SIZE);
        reset();
        // the rest of the intermediate registers, 0x87 and 0x8d are 0 and stay as they are
        if ((status & 8) == 0) {
            for (int i = 0x64; i < 0x8e; i++) regM[0][i] = 0;
            regM[0][0x80] = 0x0b;
        }
        regM[0][0x64] = 0x0f;
        regM[0][0x65] = 0x03;
        regM[0][0x66] = 0xff;
        regM[0][0x67] = 0xc6;
        regM[0][0x68] = 0x0a;
        regM[0][0x69] = 0x48;
        regM[0][0x82] = 0x08;
        setIntermediateRegister(0x64, 0x0f);
        setIntermediateRegister(0x65, 0x03);
        setIntermediateRegister(0x66, 0xff);
        setIntermediateRegister(0x67, 0xc6);
        setIntermediateRegister(0x68, 0x0a);
        setIntermediateRegister(0x69, 0x48);
        setIntermediateRegister(0x82, 0x08);
logger.log(Level.DEBUG, "chip: " + fs + " Hz, " + blockSize + " samples a block");
    }

    /** the reset of the chip (0x367e0) */
    private void reset() {
        samples = 0;
        ms = 0;
        status = (status & 0x80) | 4;
        irqStatus = 0;
        irqEnable = 0;
        dinCount0 = 0;
        dinPeriod0 = 0x535;
        dinCount1 = 0;
        dinPeriod1 = 0x29aa;
        for (int i = 3; i < 100; i++) regM[0][i] = 0;
        for (int i = 0x8e; i < 0x100; i++) regM[0][i] = 0;
        regM[0][3] = 7;
        regM[0][0x40] = 0x10;
        regM[0][0x53] = 0x40;
        regM[0][0x5a] = 0x10;
        regM[0][0x5c] = 1;
        regM[0][0x5f] = 0x40;
        regM[0][0x63] = 0x7c;
        regM[0][0xa1] = 0x7c;
        regM[0][0xa2] = 0x7c;
        regM[0][0xa3] = 0x7c;
        regM[0][0xa5] = 0x7c;
        regM[0][0xa6] = 0x7c;
        regM[0][0xa7] = 0x7c;
        for (int i = 8; i < 0x2c; i++) setIntermediateRegister(i, 0);
        for (int i = 0x2c; i < 0x4d; i++) setIntermediateRegister(i, i == 0x40 ? 0x10 : 0);
        for (int i = 0x4d; i < 0x56; i++) setIntermediateRegister(i, 0);
        setIntermediateRegister(0x53, 0x40);
        for (int i = 0x8e; i < 0x9f; i++) setIntermediateRegister(i, 0);
        for (int i = 0x56; i < 0x5a; i++) setIntermediateRegister(i, 0);
        setIntermediateRegister(0x5a, 0x10);
        setIntermediateRegister(0x5c, 1);
        setIntermediateRegister(0x5f, 0x40);
        setIntermediateRegister(0x60, 0);
        setIntermediateRegister(0x61, 0);
        setIntermediateRegister(0x62, 0);
        setIntermediateRegister(0x63, 0x7c);
        for (int i = 0x8e; i < 0xa0; i++) setIntermediateRegister(i, 0);
        // the sfx volumes of the streams (STMCONTROL_SetSfxVolume) are not of this
        setIntermediateRegister(0xa0, 0);
        setIntermediateRegister(0xa5, 0x7c);
        setIntermediateRegister(0xa6, 0x7c);
        setIntermediateRegister(0xa7, 0x7c);
        setIntermediateRegister(0xa8, 0);
        setIntermediateRegister(0xa9, 0);
        setIntermediateRegister(0xaa, 0);
        setIntermediateRegister(0xab, 0);
        setIntermediateRegister(0x29, 0);
        packetState = 3;
        readState = 3;
        // Sequencer_FifoReset(0), (1), the sequencers are not of this
        irqFifo.reset();
        clearSlots();
        for (int i = 0x18; i <= 0x28; i++) regC[i] = 0;
        for (int i = 0x7a; i <= 0x7f; i++) regC[i] = 0;
        status &= 0xfb;
    }

    /** all the slots, the channels, the streams and the ex channels to nothing (0x367e0, the intermediate register 0 bit 5) */
    private void clearSlots() {
        status |= 4;
        for (int wtSlots = 0; wtSlots < 2; wtSlots++) {
            for (int i = 0; i < 0x40; i++) {
                setControlRegister(0, (wtSlots == 1 ? 0x40 : 0) | i, 1);
                for (int r = 1; r < 10; r++) setControlRegister(r, 0, 1);
                setControlRegister(10, 0x10, 1);
                setControlRegister(10, 0, 1);
            }
        }
        for (int i = 0; i < 0x40; i++) setControlRegister(0x0b, i | 0x40, 1);
        for (int i = 0x29; i <= 0x31; i++) regC[i] = 0;
        setControlRegister(0x55, 0x10, 1);
        for (int i = 0x32; i < 0x5d; i++) setControlRegister(i, 0, 1);
        setControlRegister(0x5b, 0x40, 1);
        for (int s = 0; s < 4; s++) {
            setControlRegister(0x5d, s, 1);
            for (int r = 0x5e; r <= 0x71; r++) setControlRegister(r, 0, 1);
            setControlRegister(0x5f, 0x40, 1);
            // STMCONTROL_ResetStreamFifo
        }
        for (int i = 0; i < 16; i++) setControlRegister(0x72, 0x40 | i, 1);
        setControlRegister(0, 0, 1);
        setControlRegister(0x0b, 0, 1);
        setControlRegister(0x5d, 0, 1);
        setControlRegister(0x72, 0, 1);
        status &= 0xfb;
    }

    // ---- ports

    /** Hw_WriteReg */
    public void write(int port, int data) {
        if (!initialized) return;
        data &= 0xff;
        switch (port) {
        case 0 -> {
            int v = status;
            if ((v & 0x40) != 0 && (data & 0x40) != 0) v = status & ~0x40;
            if ((v & 2) != 0 && (data & 2) != 0) v &= 0xcd;
            status = (v & 0x7f) | (data & 0x80);
        }
        case 1 -> iregIndex = data;
        case 2 -> setIntermediateRegister(iregIndex, data);
        case 3 -> writePacket(data);
        case 4, 5, 6, 7, 8, 9 -> {} // the sequencers and the streams are not of this
        case 10 -> writeReadRequest(data);
        case 11 -> irqStatus &= ~(irqStatus & irqEnable & data) & 0xffff;
        case 12 -> irqStatus &= ~(irqStatus & irqEnable & (data << 8)) & 0xffff;
        case 13 -> irqEnable = data | (irqEnable & 0xff00);
        case 14 -> irqEnable = (irqEnable & 0xff) | (data << 8);
        default -> {}
        }
    }

    /** port 3 */
    private void writePacket(int data) {
        switch (packetState) {
        case 3 -> {
            packetAddress = data & 0x7f;
            packetState = (data >> 7) == 0 ? 4 : 8;
        }
        case 4 -> {
            packetAddress |= (data & 0x7f) << 7;
            packetState = (data >> 7) == 0 ? 5 : 8;
        }
        case 5 -> {
            packetState = 6;
            packetAddress = ((data & 0x7f) << 14 | packetAddress) & 0x1ffff;
        }
        case 6 -> {
            packetCount = data & 0x7f;
            if ((data >> 7) == 0) {
                packetState = 7;
            } else {
                packetState = (data & 0x7f) != 0 ? 9 : 3;
            }
        }
        case 7 -> {
            packetCount |= (data & 0x7f) << 7;
            packetState = packetCount != 0 ? 9 : 3;
        }
        case 8 -> {
            setControlRegister(packetAddress & 0xff, data, 0);
            packetAddress++;
            if ((data >> 7) != 0) packetState = 3;
        }
        case 9 -> {
            setMemory(packetAddress, data);
            packetCount--;
            packetAddress++;
            if (packetCount == 0) packetState = 3;
        }
        default -> packetState = 3;
        }
    }

    /** port 10 */
    private void writeReadRequest(int data) {
        switch (readState) {
        case 3 -> {
            readAddress = data & 0x7f;
            readState = (data >> 7) == 0 ? 4 : 8;
        }
        case 4 -> {
            readAddress |= (data & 0x7f) << 7;
            readState = (data >> 7) == 0 ? 5 : 8;
        }
        case 5 -> {
            readState = 9;
            readAddress |= (data & 0x7f) << 14;
        }
        case 8 -> {
            if ((data & 1) != 0) break;
            readLatch = readControlRegister(readAddress & 0xff);
            status |= 2;
            readState = 3;
        }
        case 9 -> {
            if ((data & 1) != 0) break;
            readLatch = memory(readAddress);
            readState = 3;
            status |= 2;
        }
        default -> readState = 3;
        }
    }

    /** a control register read back (Hw_WriteReg case 10, 8) */
    private int readControlRegister(int address) {
        if (address < 0x0b) {
            int[] info = (regC[0] & 0x40) == 0 ? fmInfo[regC[0] & 0x3f] : wtInfo[regC[0] & 0x3f];
            return address >= 1 ? info[address - 1] : regC[0];
        } else if (address < 0x18) {
            int[] info = chInfo[regC[0x0b] & 0x3f];
            return address == 0x0b ? regC[0x0b] : info[address - 0x0c];
        } else if (address < 0x27 || (address >= 0x29 && address < 0x5d)) {
            return regC[address];
        } else if (address == 0x27 || address == 0x28) {
            return 0xff;
        } else if (address < 0x72) {
            int[] info = streamInfo[regC[0x5d] & 3];
            return address == 0x5d ? regC[0x5d] : info[address - 0x5e];
        } else if (address < 0x7a) {
            int[] info = exInfo[regC[0x72] & 0xf];
            return address == 0x72 ? regC[0x72] : info[address - 0x73];
        } else {
            return (readAddress & 0x80) != 0 ? 0xff : regC[address];
        }
    }

    /** Hw_ReadReg, the streams and the irq status are not of this */
    public int read(int port) {
        if (!initialized) return 0xff;
        return switch (port) {
            case 0 -> status;
            case 1 -> iregIndex;
            case 2 -> readIntermediateRegister(iregIndex);
            case 3 -> {
                int v = readLatch;
                status &= 0xcd;
                yield v;
            }
            case 4 -> irqFifo.get();
            case 5 -> 0x71;
            default -> 0;
        };
    }

    // ---- the control registers

    /** ARM::SetCtrlReg */
    void setControlRegister(int address, int data, int reset) {
        address &= 0xff;
        data &= 0xff;
        if ((address & 0x80) != 0) return;
        if (address <= 0x0a) {
            if (address == 0) {
                regC[0] = data & 0x7f;
                return;
            }
            int slot = regC[0] & 0x3f;
            if ((regC[0] & 0x40) != 0) {
                wt.setVoiceRegister(slot, address, data & 0x7f, reset);
            } else {
                fm.setVoiceRegister(slot, address, data & 0x7f, reset);
            }
        } else if (address <= 0x17) {
            if (address == 0x0b) {
                int ch = data & 0x3f;
                regC[0x0b] = ch;
                if ((data & 0x40) != 0) {
                    resetChannel(ch);
                }
                return;
            }
            setChannelRegister(regC[0x0b] & 0x3f, address, data & 0x7f);
        } else if (address <= 0x25) {
            if ((address & 1) == 0) {
                regC[address] = data & 0x0f;
                return;
            }
            int d = data & 0x7f;
            regC[address] = d;
            int hi = regC[address - 1];
            status |= irqFifo.set(d | ((hi | (((address - 0x18) >> 1) << 4 | 0x80)) << 8));
        } else if (address == 0x26) {
            if ((data & 3) == 0) return;
            int v;
            if ((data & 1) != 0) {
                // stops the sequencer 0 when it runs
                int st = regM[0][0x0b];
                if ((st & 1) != 0) regM[0][0x0b] = st & 0xfe;
                v = (data & 2) != 0 ? 0xf103 : 0xf101;
            } else {
                v = 0xf102;
            }
            if ((data & 2) != 0) {
                int st = regM[0][0x0b];
                if ((st & 2) != 0) regM[0][0x0b] = st & 0xfd;
            }
            status |= irqFifo.set(v);
        } else if (address == 0x27 || address == 0x28) {
            // nothing
        } else if (address <= 0x31) {
            fm.setWaveRegister(address, data & 0x7f);
            // HVCONTROL_SetWave is not of this
        } else if (address <= 0x5c) {
            regC[address] = data & 0x7f; // HVCONTROL_SetHvVoiceReg is not of this
        } else if (address <= 0x71) {
            if (address == 0x5d) {
                regC[0x5d] = data & 3;
                return;
            }
            streamInfo[regC[0x5d] & 3][address - 0x5e] = data & 0x7f; // STMCONTROL_SetStreamVoiceReg is not of this
        } else if (address <= 0x79) {
            if (address == 0x72) {
                int ex = data & 0x0f;
                regC[0x72] = ex;
                if ((data & 0x40) != 0) {
                    resetExChannel(ex);
                }
                return;
            }
            setExChannelRegister(regC[0x72] & 0x0f, address, data & 0x7f);
        } else {
            if (address == 0x7b) {
                regC[0x7b] = data & 7;
            } else if (address == 0x7d) {
                regC[0x7d] = data & 3;
            } else {
                regC[address] = data & 0x7f;
            }
            dsp.setVoiceRegister(address, data);
        }
    }

    /** VIRTUALREGISTER_ChReset and the channel */
    private void resetChannel(int ch) {
        int[] info = chInfo[ch];
        info[0] = 0x60;
        info[1] = 0x40;
        info[2] = 0;
        info[3] = 0;
        info[4] = 8;
        for (int i = 5; i < 11; i++) info[i] = 0;
        info[11] = 0x7c;
        channels[ch].reset();
    }

    /** VIRTUALREGISTER_ExChReset and the ex channel */
    private void resetExChannel(int ex) {
        int[] info = exInfo[ex];
        info[0] = 0x60;
        info[1] = 0x40;
        info[2] = 8;
        info[3] = 0;
        info[4] = 8;
        info[5] = 0;
        info[6] = 0;
        ExChannel e = exChannels[ex];
        e.mode = 0;
        e.out = 0;
        e.volume = 0x19;
        e.pan = 0x10;
        e.pitchBend = 0x10000;
        e.pitchBend2 = 0x10000;
    }

    /** ARM::CCh::SetChParamReg */
    private void setChannelRegister(int ch, int address, int d) {
        int[] info = chInfo[ch];
        Channel c = channels[ch];
        switch (address) {
        case 0x0c -> { // SetChVolume
            info[0] = d & 0x7d;
            c.volume = ((d >> 2) & 0x1f) | (d << 31);
        }
        case 0x0d -> { // SetChPanpot
            info[1] = d & 0x7d;
            c.pan = ((d >> 2) & 0x1f) | (d << 31);
        }
        case 0x0e -> { // SetHold1, SetToremolo
            info[2] = d & 0x71;
            c.hold1 = d & 1;
            int t = (d >> 4) & 7;
            c.tremolo = switch (t) {
                case 2, 3 -> 2;
                case 4, 5 -> 3;
                case 6, 7 -> 4;
                default -> t;
            };
        }
        case 0x0f -> { // SetModulation
            info[3] = d & 0x5f;
            int v = d & 0x1f;
            if (((d >> 6) & 1) != 0) {
                c.modulation = (v | 0x80) & 0xff;
            } else {
                c.modulation = switch (v) {
                    case 0, 1, 2, 4 -> v;
                    case 3 -> 2;
                    case 5 -> 4;
                    default -> 8;
                };
            }
        }
        case 0x10 -> info[4] = d & 0x7f;
        case 0x11 -> { // SetPitchBend
            info[5] = d & 0x7f;
            c.pitchBend = ((info[4] << 7) | info[5]) << 6;
        }
        case 0x12 -> { // SetResonance
            info[6] = d & 0xfe;
            c.resonance = ((d >> 1) & 0x3f) ^ 0x20;
        }
        case 0x13 -> { // SetBrightness
            info[7] = d & 0x7f;
            c.brightness = d ^ 0x40;
        }
        case 0x14 -> { // SetExId
            info[8] = d & 0x7f;
            c.exId = d & 0xf;
            c.exOn = (d >> 6) & 1;
            c.exMode = (d >> 4) & 3;
        }
        case 0x15 -> { // SetSfx1Volume
            info[9] = d & 0x7c;
            c.sfx1 = (d >> 2) & 0x1f;
        }
        case 0x16 -> { // SetSfx2Volume
            info[10] = d & 0x7c;
            c.sfx2 = (d >> 2) & 0x1f;
        }
        case 0x17 -> { // SetDryVolume
            info[11] = d & 0x7c;
            c.dry = (d >> 2) & 0x1f;
        }
        default -> {}
        }
    }

    /** ARM::CCh::SetExParamReg */
    private void setExChannelRegister(int ex, int address, int d) {
        if (ex - 0xd >= 0 && ex - 0xd <= 2) return; // 13 ~ 15 are not taken
        int[] info = exInfo[ex];
        ExChannel e = exChannels[ex];
        switch (address) {
        case 0x73 -> {
            info[0] = d & 0x7d;
            e.volume = ((d >> 2) & 0x1f) | (d << 31);
        }
        case 0x74 -> {
            info[1] = d & 0x7d;
            e.pan = ((d >> 2) & 0x1f) | (d << 31);
        }
        case 0x75 -> info[2] = d & 0x1f;
        case 0x76 -> {
            info[3] = d & 0x7f;
            e.pitchBend = (info[3] | (info[2] << 7)) << 6;
        }
        case 0x77 -> info[4] = d & 0x1f;
        case 0x78 -> {
            info[5] = d & 0x7f;
            e.pitchBend2 = (info[5] | (info[4] << 7)) << 6;
        }
        case 0x79 -> {
            info[6] = d & 0x63;
            e.mode = (d >> 5) & 3;
            e.out = d & 3;
        }
        default -> {}
        }
    }

    // ---- the intermediate registers

    /** 0x378fc */
    void setIntermediateRegister(int index, int data) {
        index &= 0xff;
        data &= 0xff;
        if (index < 8) {
            switch (index) {
            case 0 -> {
                int old = regM[0][0];
                if ((old & 0x80) == 0 && (data & 0x80) != 0) {
                    reset();
                }
                old = regM[0][0];
                if ((old & 0x40) == 0 && (data & 0x40) != 0) {
                    status |= 4;
                    dsp.reset(true);
                    status &= 0xfb;
                } else {
                    dsp.reset(false);
                }
                old = regM[0][0];
                if ((old & 0x20) == 0 && (data & 0x20) != 0) {
                    clearSlots();
                }
                regM[0][0] = data & 0xe0;
            }
            case 1 -> {
                int old = regM[0][1];
                if ((old & 0x80) == 0 && (data & 0x80) != 0) {
                    packetState = 3;
                    readState = 3;
                    packetAddress = 0;
                    packetCount = 0;
                    readAddress = 0;
                }
                // the sequencers' fifos are not of this
                if ((old & 0x10) == 0 && (data & 0x10) != 0) {
                    irqFifo.reset();
                }
                regM[0][1] = data & 0xf0;
            }
            case 2 -> regM[0][2] = data & 0x80;
            case 3 -> {}
            default -> regM[0][index] = data;
            }
            return;
        }
        if ((status & 8) != 0) return;
        if (index <= 0x29) {
            switch (index) {
            case 0x08 -> regM[0][index] = data & 0x87;
            case 0x09, 0x0a, 0x0c, 0x0d, 0x0e, 0x0f, 0x1c -> regM[0][index] = data;
            case 0x0b -> regM[0][index] = data & 3;
            case 0x14 -> regM[0][index] = data & 3;
            case 0x18, 0x19 -> regM[0][index] = data & 0x7f;
            case 0x1a -> {
                regM[0][index] = data & 0x7f;
                fm.setVolume(data & 3, volumeTable[(data & 0x7f) >> 2]);
            }
            case 0x1b -> {
                regM[0][index] = data & 0x7f;
                wt.setVolume(data & 3, volumeTable[(data & 0x7f) >> 2]);
            }
            case 0x1d -> regM[0][index] = data & 0x7f; // HVCONTROL_SetVolume is not of this
            case 0x20 -> {
                regM[0][index] = data & 0x7f;
                timer.setMsTimer(0, data & 0x7f);
                timer.setMsTimer(1, data & 0x7f);
            }
            case 0x22 -> {
                regM[0][index] = data & 0x7f;
                timer.setCounter(0, data & 0x7f);
            }
            case 0x23, 0x24, 0x25 -> {
                int v = index == 0x23 ? data & 7 : data & 1;
                regM[0][index] = v;
                int t0 = regM[0][0x23], t1 = regM[0][0x24], t2 = regM[0][0x25];
                int c = (t1 & 1) | (t0 & 7) | (t2 & 1);
                if ((c & 4) != 0) {
                    c = (regM[0][0x0b] & c & 1) | (t0 & 6);
                }
                timer.control(0, c, ms);
            }
            case 0x27 -> {
                regM[0][index] = data & 0x7f;
                timer.setCounter(1, data & 0x7f);
            }
            case 0x28 -> {
                int v = data & 7;
                int c = v;
                if ((data & 4) != 0) c = (regM[0][0x0b] & v & 1) | (data & 6);
                timer.control(1, c, ms);
                regM[0][index] = v;
            }
            case 0x29 -> regM[0][index] = data & 7;
            default -> {} // not kept
            }
            return;
        }
        if (index >= 0x2c && index <= 0x4c || index == 0x7c || index == 0x7f) {
            dsp.setIntermediateRegister(index, data);
            return;
        }
        if (index >= 0x4d && index <= 0x55) {
            switch (index) {
            case 0x4d, 0x50 -> regM[0][index] = data & 0x7c;
            case 0x4e, 0x51 -> regM[0][index] = data & 0x3f;
            case 0x4f, 0x52 -> regM[0][index] = data & 0x7f;
            case 0x53 -> regM[0][index] = data & 0xfc;
            case 0x54 -> regM[0][index] = data & 0x7c;
            case 0x55 -> regM[0][index] = data & 0x4f;
            default -> {}
            }
            return; // dtmf, not of this
        }
        if (index >= 0x56 && index <= 0x63) {
            switch (index) {
            case 0x57, 0x59 -> regM[0][index] = data;
            case 0x5a, 0x5b -> regM[0][index] = data & 0x7f;
            case 0x5c, 0x5d -> regM[0][index] = data & 0x87;
            case 0x5e -> regM[0][index] = data & 3;
            case 0x56, 0x58 -> regM[0][index] = data;
            default -> {} // the din parameters, kept in the din info
            }
            return; // din, not of this
        }
        if (index >= 0x64 && index <= 0x69) {
            if (index == 0x66) {
                int v = 0;
                if ((data & 1) == 0) {
                    int a = (data & 0x10) == 0 ? 0x0b : 0x0d;
                    int b = (data & 0x10) == 0 ? 0x13 : 0x15;
                    v = (data & 0x20) == 0 ? a : b;
                }
                regM[0][0x80] = v;
            }
            regM[0][index] = data;
            return;
        }
        if (index >= 0x6a && index <= 0x80) {
            if (index == 0x80) return;
            regM[0][index] = data;
            return;
        }
        if (index >= 0x81 && index <= 0x85) {
            regM[0][index] = data;
            return;
        }
        if (index >= 0x86 && index <= 0x8d) {
            switch (index) {
            case 0x86 -> regM[0][index] = data & 7;
            case 0x87 -> {
                int old = readIntermediateRegister(0x87);
                if (((old ^ (data & 0x77)) & 0x70) != 0) {
                    int r = (data >> 4) & 7;
                    if (r > 4) r = 0;
                    int period = rom.s32(0x17beb0 + r * 4);
                    dinCount0 = 0;
                    dinPeriod0 = period != 0 ? Integer.divideUnsigned(fs, period) : 0;
                }
                regM[0][index] = data & 0x77;
            }
            case 0x8c -> regM[0][index] = data & 1;
            case 0x8d -> {
                int old = readIntermediateRegister(0x8d);
                if (((old ^ (data & 0x77)) & 0x70) != 0) {
                    int r = (data >> 4) & 7;
                    if (r > 4) r = 0;
                    int period = rom.s32(0x17bed0 + r * 4);
                    dinCount1 = 0;
                    dinPeriod1 = period != 0 ? Integer.divideUnsigned(fs << 1, period) : 0;
                }
                regM[0][index] = data & 0x77;
            }
            default -> regM[0][index] = data & 0x77;
            }
            return;
        }
        if (index >= 0x8e && index <= 0x9e) {
            switch (index - 0x8e) {
            case 0 -> regM[0][index] = data & 0xf;
            case 3, 7, 0xb, 0xf -> regM[0][index] = data & 0x7f;
            default -> regM[0][index] = data; // the eq of the dsp 1, not of the midi
            }
            return;
        }
        if (index == 0x9f) return;
        if (index >= 0xa0 && index <= 0xa3) {
            if (index == 0xa0) regM[0][index] = data & 3; else regM[0][index] = data & 0x7c;
            return; // the sfx of the streams
        }
        if (index >= 0xa5 && index <= 0xa7) {
            regM[0][index] = data & 0x7c;
            return; // the human voice
        }
        if (index >= 0xa8 && index <= 0xab) {
            regM[0][index] = data & 0x7f;
            return; // the 3d
        }
    }

    /** 0x315cc */
    int readIntermediateRegister(int index) {
        index &= 0xff;
        if (index < 8) {
            if (index == 3) {
                // the sequencers' fifos empty and not full, the irq fifo not full
                return 7 | (irqFifo.status() & 2) << 6;
            }
            return regM[0][iregIndex];
        }
        if ((status & 8) != 0) return 0xff;
        return switch (index) {
            case 0x10, 0x5f, 0x60, 0x61, 0x62, 0x63 -> 0; // the sequencer's counter, din
            case 0x21 -> timer.counter(0);
            case 0x26 -> timer.counter(1);
            case 0xfc, 0xfd -> {
                int v = regM[0][index];
                regM[0][index] = 0;
                yield v;
            }
            default -> regM[0][index];
        };
    }

    // ---- rendering

    /**
     * Hw_GenerateEx
     * @param left 16 bit range, written
     * @param right 16 bit range, written
     */
    public void generate(int[] left, int[] right, int offset, int length) {
        if (!initialized) return;
        while (length > 0) {
            int n = Math.min(length, blockSize);
            samples += n;
            ms = fs != 0 ? samples * 1000 / fs : 0;
            int t = timer.generate(ms);
            irqStatus |= (t & 3) << 5;
            // Sequencer_Generate, the sequencers are not of this
            for (int[] b : bus) java.util.Arrays.fill(b, 0, n, 0);
            fm.generate(n, bus);
            wt.generate(n, bus);
            // STMCONTROL_Generate, HVCONTROL_Generate are not of this, but for the noise
            // HVCONTROL_Generate's CCsmSynth takes a block, which is what the others get after it
            noise.generate();
            int r18 = regM[0][0x18], r19 = regM[0][0x19];
            dsp.setMasterVolume(volumeTable[(r18 >> 2) & 0x1f] << (r18 & 3), volumeTable[(r19 >> 2) & 0x1f] << (r19 & 3));
            dsp.generate(n, bus);
            System.arraycopy(bus[0], 0, left, offset, n);
            System.arraycopy(bus[1], 0, right, offset, n);
            offset += n;
            length -= n;
        }
    }
}
