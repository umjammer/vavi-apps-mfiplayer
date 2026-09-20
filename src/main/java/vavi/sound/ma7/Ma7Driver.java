/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;


/**
 * The driver of the real time midi path of {@code libM7_EmuSmw7.so} (YAMAHA::MaRmdCnv, MaCmd, MaDva,
 * MaDevDrv), midi messages to the packets of {@link Ma7Chip}.
 * <p>
 * only the immediate mode, which the real time midi takes, is ported, packets are sent as they are
 * made, of the sequence 0, the sequencer mode 0.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public final class Ma7Driver {

    /**
     * the port writes of the initialization of the driver and the opening of a real time midi sequence,
     * "&lt;port&gt;&lt;data&gt;" in hex, P: the dsp program, C: the dsp coefficients.
     */
    private static final String INIT = """
            000 16a 200 16b 200 16c 200 16d 200 172 200 173 200 174 200 175 200 177 200 178 200 179 200 17a 200 17b 200 17c 200
            168 20c 169 248 164 20e 166 27f 164 20c 208 200 100 2c0 200 000 103 002 080 000 103 002 080 000 103 002 080 000 103
            002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002
            080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080
            000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 000 103 002 080 166 27e 180 166 276
            166 274 167 2c4 166 270 167 2c0 16d 201 166 260 180 16c 211 172 21f 173 21f 120 24d 122 207 123 201 080 118 27c 119
            27c 11a 27c 11b 27c 11c 27c 1a8 27c 1a9 27c 1aa 27c 1ab 27c 129 204 11d 27f 1a0 200 1a1 200 1a2 200 1a3 27c 1a0 201
            1a1 200 1a2 200 1a3 27c 1a0 202 1a1 200 1a2 200 1a3 27c 1a0 203 1a1 200 1a2 200 1a3 27c 17e 200 000 130 280 080 000
            130 200 080 000 13a 212 080 000 13b 201 080 000 130 201 131 200 132 200 P 080 000 130 200 131 200 132 200 C 080 000
            130 200 131 202 132 285 137 200 138 200 080 000 130 200 131 202 132 28c 137 200 138 200 080 000 130 200 131 202 132
            284 137 200 138 200 080 000 130 200 131 202 132 28b 137 200 138 200 080 000 130 200 131 202 132 283 137 200 138 200
            080 000 130 200 131 202 132 28a 137 200 138 200 080 000 130 200 131 202 132 282 137 200 138 200 080 000 130 200 131
            202 132 289 137 200 138 200 080 000 130 200 131 200 132 200 137 2ff 138 2ff 080 000 130 240 080 000 130 240 131 200
            132 200 137 2ff 138 2ff 080 000 130 240 131 200 132 200 137 2ff 138 2ff 080 000 130 240 131 200 132 200 137 2ff 138
            2ff 080 000 130 240 131 200 132 200 137 2ff 138 2ff 080 000 130 240 131 200 132 2d2 137 25a 138 282 080 000 130 240
            131 200 132 2d4 137 25a 138 282 080 000 130 240 131 200 132 274 137 200 138 200 080 000 130 240 131 200 132 275 137
            200 138 200 080 000 130 240 131 200 132 200 137 2ff 138 2ff 080 000 101 250 080 000 101 200 080 000 10b 210 080 000
            108 204 080 000 109 20e 080 000 10a 266 080 000 103 38b 380 395 300 300 3fc 38b 381 395 300 300 3fc 38b 382 395 300
            300 3fc 38b 383 395 300 300 3fc 38b 384 395 300 300 3fc 38b 385 395 300 300 3fc 38b 386 395 300 300 3fc 38b 387 395
            300 300 3fc 38b 388 395 300 300 3fc 38b 389 395 300 300 3fc 38b 38a 395 300 300 3fc 38b 38b 395 300 300 3fc 38b 38c
            395 300 300 3fc 38b 38d 395 300 300 3fc 38b 38e 395 300 300 3fc 38b 38f 395 300 300 3fc 38b 390 395 300 300 3fc 38b
            391 395 300 300 3fc 38b 392 395 300 300 3fc 38b 393 395 300 300 3fc 38b 394 395 300 300 3fc 38b 395 103 395 300 300
            3fc 38b 396 395 300 300 3fc 38b 397 395 300 300 3fc 38b 398 395 300 300 3fc 38b 399 395 300 300 3fc 38b 39a 395 300
            300 3fc 38b 39b 395 300 300 3fc 38b 39c 395 300 300 3fc 38b 39d 395 300 300 3fc 38b 39e 395 300 300 3fc 38b 39f 395
            300 300 3fc 38b 3a0 395 300 300 3fc 38b 3a1 395 300 300 3fc 38b 3a2 395 300 300 3fc 38b 3a3 395 300 300 3fc 38b 3a4
            395 300 300 3fc 38b 3a5 395 300 300 3fc 38b 3a6 395 300 300 3fc 38b 3a7 395 300 300 3fc 38b 3a8 395 300 300 3fc 38b
            3a9 395 300 300 3fc 38b 3aa 395 300 103 300 3fc 38b 3ab 395 300 300 3fc 38b 3ac 395 300 300 3fc 38b 3ad 395 300 300
            3fc 38b 3ae 395 300 300 3fc 38b 3af 395 300 300 3fc 38b 3b0 395 300 300 3fc 38b 3b1 395 300 300 3fc 38b 3b2 395 300
            300 3fc 38b 3b3 395 300 300 3fc 38b 3b4 395 300 300 3fc 38b 3b5 395 300 300 3fc 38b 3b6 395 300 300 3fc 38b 3b7 395
            300 300 3fc 38b 3b8 395 300 300 3fc 38b 3b9 395 300 300 3fc 38b 3ba 395 300 300 3fc 38b 3bb 395 300 300 3fc 38b 3bc
            395 300 300 3fc 38b 3bd 395 300 300 3fc 38b 3be 395 300 300 3fc 38b 3bf 395 300 300 3fc 080 000 103 3f2 300 365 341
            308 300 308 300 380 080 000 11b 27e 080 000 10c 200 080 000 10d 200 080 000 10e 200 080 000 10f 200 080 000 103 3f2
            300 3e5 080 000 130 240 131 200 132 2d2 137 25a 138 282 080 000 130 240 131 200 132 2d4 137 25a 138 282 080 000 130
            240 131 200 132 274 137 200 138 200 080 000 130 240 131 200 132 275 137 200 138 200 080 000 130 240 131 200 132 200
            137 2ff 138 2ff 080
            """;

    /** the dsp program the driver writes at the initialization, 768 words of 6 bytes */
    private static final int DSP_PROGRAM = 0x439870;
    /** the dsp coefficients the driver writes at the initialization, 768 words of 2 bytes */
    private static final int DSP_COEFFICIENTS = 0x439270;

    /** the sequence the real time midi takes */
    private static final int SEQ = 0;

    private final Ma7Chip chip;

    /** for debugging, the port accesses, "W|R port data" */
    private final Consumer<String> portLog;

    // tables of the driver

    /** a level of 7 bit to a gain in dB, 0 ~ 0x30 */
    private final int[] volumeDb;
    /** a gain in dB to a level of 7 bit */
    private final int[] volumeLevel;
    /** cc 1 to the modulation depth, 0 ~ 4 */
    private final int[] modulationTable;
    /** the modulation depth to the register */
    private final int[] modulationRegister;
    /** cc 71, 74 to the register */
    private final int[] filterTable;
    /** cc 90, 91, 93 to the register */
    private final int[] sendTable;
    /** banks 0x7c, 0x7d */
    private final int[] bankTable;

    final Ma7Dva dva;

    /** a channel of the converter (_MARMDCNV_INFO) */
    private static final class ConverterChannel {
        int volume = 100;
        int expression = 0x7f;
        /** msb &lt;&lt; 8 | lsb */
        int bank = 0x7900;
        /** msb &lt;&lt; 8 | lsb, bit 15: nrpn */
        int rpn = 0x7f7f;
        int data = 0x2000;
    }

    private final ConverterChannel[] converter = new ConverterChannel[16];
    private int masterVolumeConverter = 0x7f;

    /** a channel of the driver (+0x410 + channel * 0x1e) */
    private static final class Channel {
        /** +1 */
        int keyControl;
        /** +2 */
        int monoCount;
        /** +3 */
        int drumChannel = 0xff;
        /** +4, bit 7: drum */
        int bank;
        /** +5 */
        int program;
        /** +6, 1: poly, 0: mono */
        int poly = 1;
        /** +7 */
        int volume = 100;
        /** +9 */
        int volume2 = 0x7f;
        /** +0xa */
        int pan = 0x40;
        /** +0xb */
        int expression = 0x7f;
        /** +0xc */
        int holdMode = 1;
        /** +0xd */
        int hold;
        /** +0xe */
        int bendRange = 2;
        /** +0xf, the channel register of a note */
        int register;
        /** +0x10 */
        int registerFixed;
        /** +0x11 */
        int dry;
        /** +0x12 */
        int reverb;
        /** +0x13 */
        int chorus;
        /** +0x14 */
        int bend = 0x400;
        /** +0x16 */
        int tune = 0x400;
        /** +0x18 */
        int coarse = 0x400;
        /** +0x1a */
        int fine = 0x400;
        /** +0x1c, the key of the voice of the last note */
        int key;
    }

    private final Channel[] channels = new Channel[16];
    /** +0x7d2 */
    private int masterVolume = 0x7f;
    /** +0x7d3 */
    private int masterVolume2 = 0x4c;
    /** +0x7d6, the drum set */
    private final int drumSet = 1;
    /** +0x7d7 */
    private final int velocityCurve = 0;
    /** +0x7dc */
    private int masterCoarse = 0x400;
    /** +0x7de */
    private int masterFine = 0x400;
    /** the reverb, the chorus of the sequence (+0x38, +0x39 of the sequence info) */
    private final boolean reverb = true, chorus = true;

    private final Ma7Rom rom;

    // the voices a song registers (the sequence info of the library, MaCmd_Set/GetMelody, Drum, Voice)

    /** how many voices a sequence can have registered at once (MA_MAX_REG_VOICE * 2) */
    private static final int REGISTERED_VOICES = 256;
    /** the ram of the chip, where a registered voice and a wave of a song go */
    private static final int RAM = Ma7Chip.ROM_SIZE;
    private static final int RAM_SIZE = Ma7Chip.MEMORY_SIZE - Ma7Chip.ROM_SIZE;

    /** a melody voice by bank * 0x80 + program, 0x8000 | the voice: registered (+0x2e8) */
    private final short[] melody = new short[128 * 128];
    /** a drum voice by (bank - 0x80) * 0x80 + key (+0x1ae8) */
    private final short[] drum = new short[128 * 128];
    /** the multiplier of an operator of a song as the chip has it, 11, 13 and 14 are none of its */
    private static final int[] MULTIPLIER = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 12, 12, 15, 15 };
    /** the address in the ram of a registered voice (+0x45d0) */
    private final int[] voiceAddress = new int[REGISTERED_VOICES];
    /** the key of a registered voice: an fm voice's own key, a wave table one's sampling rate */
    private final int[] voiceKey = new int[REGISTERED_VOICES];
    /** 0: fm, 1: wave table */
    private final int[] voiceType = new int[REGISTERED_VOICES];
    /** the next free one of them */
    private int voices;
    /** the address of the wave of a wave table voice, by its id (+0xf4) */
    private final int[] wtWave = new int[128];
    /** where the next voice or wave of a song goes, and what is left of the ram */
    private int ramAddress = RAM, ramLeft = RAM_SIZE;

    /** the packet being made */
    private final ByteArrayOutputStream packet = new ByteArrayOutputStream();

    /** the driver initialized and a real time midi sequence opened */
    public Ma7Driver(Ma7Rom rom, Ma7Chip chip) {
        this(rom, chip, null);
    }

    /** @param portLog for debugging, the port accesses, "W|R port data" */
    Ma7Driver(Ma7Rom rom, Ma7Chip chip, Consumer<String> portLog) {
        this.rom = rom;
        this.chip = chip;
        this.portLog = portLog;
        volumeDb = table(0x42ee00, 128);
        volumeLevel = table(0x42ee80, 0xc1);
        modulationTable = table(0x38ff50, 128);
        modulationRegister = table(0x4313d0, 5);
        filterTable = table(0x4313e0, 128);
        sendTable = table(0x431350, 128);
        bankTable = table(0x38ffd0, 128);
        dva = new Ma7Dva(rom, 4, 32, 32);
        for (int i = 0; i < 16; i++) {
            converter[i] = new ConverterChannel();
            channels[i] = new Channel();
        }
        converter[9].bank = 0x7800;
        channels[9].bank = 0x80;
        initialize();
    }

    private int[] table(int va, int n) {
        int[] t = new int[n];
        for (int i = 0; i < n; i++) t[i] = rom.u8(va + i);
        return t;
    }

    /** replays {@link #INIT} */
    private void initialize() {
        for (String op : INIT.trim().split("\\s+")) {
            switch (op) {
            case "P" -> {
                for (int i = 0; i < 768; i++) {
                    for (int j = 0; j < 6; j++) {
                        write(1, 0x33 + j);
                        write(2, rom.u8(DSP_PROGRAM + i * 6 + j));
                    }
                }
            }
            case "C" -> {
                for (int i = 0; i < 768; i++) {
                    for (int j = 0; j < 2; j++) {
                        write(1, 0x37 + j);
                        write(2, rom.u8(DSP_COEFFICIENTS + i * 2 + j));
                    }
                }
            }
            default -> {
                int v = Integer.parseInt(op, 16);
                write(v >> 8, v & 0xff);
            }
            }
        }
    }

    // MaDevDrv

    /** MaDevDrv_SendDirectPacket */
    private void send() {
        byte[] p = packet.toByteArray();
        packet.reset();
        if (p.length == 0) return;
        write(0, 0x00); // DisableIrq
        for (int i = 0; i < p.length; i += 0x80) {
            write(1, 3); // WaitFifoEmpty
            read(2);
            for (int j = i; j < Math.min(p.length, i + 0x80); j++) write(3, p[j] & 0xff);
        }
        write(0, 0x80); // EnableIrq
    }

    private void write(int port, int data) {
        if (portLog != null) portLog.accept(String.format("W %x %02x", port, data));
        chip.write(port, data);
    }

    private int read(int port) {
        int v = chip.read(port);
        if (portLog != null) portLog.accept(String.format("R %x %02x", port, v));
        return v;
    }

    private void put(int... bytes) {
        for (int b : bytes) packet.write(b);
    }

    // MaRmdCnv

    /** MaSmw_Ctrl 0x36: MaRmdCnv_ChannelMessage */
    public void message(int status, int data1, int data2) {
        int ch = status & 0xf;
        data1 &= 0x7f;
        data2 &= 0x7f;
        if (status < 0x80) return;
        ConverterChannel c = converter[ch];
        switch (status & 0xf0) {
        case 0x80 -> noteOff(ch, data1);
        case 0x90 -> {
            if (data2 == 0) noteOff(ch, data1); else noteOn(ch, data1, data2);
        }
        case 0xc0 -> {
            int bank, program = data1;
            switch (c.bank >> 8) {
            case 0x78 -> { bank = 0x80; program = 0; }
            case 0x79 -> bank = 0;
            case 0x7c -> bank = bankTable[c.bank & 0x7f];
            case 0x7d -> { bank = bankTable[data1] + 0x80; program = 0; }
            default -> {
                if (ch == 9) { bank = 0x80; program = 0; } else bank = 0;
            }
            }
            programChange(ch, bank, program);
        }
        case 0xe0 -> pitchBend(ch, data1 | data2 << 7);
        case 0xb0 -> {
            switch (data1) {
            case 0 -> { c.bank = (c.bank & 0xff) | data2 << 8; nop(); }
            case 0x20 -> { c.bank = data2 | (c.bank & 0xff00); nop(); }
            case 1 -> modulation(ch, modulationTable[data2]);
            case 6 -> {
                switch (c.rpn) {
                case 0 -> bendRange(ch, data2);
                case 1 -> { c.data = data2 << 7; fineTune(ch, c.data); }
                case 2 -> coarseTune(ch, data2);
                default -> nop();
                }
            }
            case 0x26 -> {
                if (c.rpn == 1) fineTune(ch, data2 | c.data & 0x3f80); else nop();
            }
            case 7 -> { c.volume = data2; channelVolume(ch, data2); }
            case 10 -> panpot(ch, data2);
            case 11 -> { c.expression = data2; expression(ch, data2); }
            case 0x40 -> hold(ch, data2 > 0x3f ? 1 : 0);
            case 0x47 -> resonance(ch, data2);
            case 0x4a -> brightness(ch, data2);
            case 0x5a -> send(ch, data2, 0x97, 10);
            case 0x5b -> send(ch, data2, 0x95, 11);
            case 0x5d -> send(ch, data2, 0x96, 12);
            case 0x62, 0x63 -> { c.rpn |= 0x8000; nop(); }
            case 0x64 -> { c.rpn = data2 | (c.rpn & 0x7f00); nop(); }
            case 0x65 -> { c.rpn = (c.rpn & 0x7f) | data2 << 8; nop(); }
            case 0x78 -> allOff(ch, true, false);
            case 0x79 -> {
                c.expression = 0x7f;
                c.rpn = 0x7f7f;
                resetAllControllers(ch);
            }
            case 0x7b -> allOff(ch, false, false);
            case 0x7e -> { if (data2 == 1) mono(ch, true); else nop(); }
            case 0x7f -> { if (data2 == 0) mono(ch, false); else nop(); }
            default -> nop();
            }
        }
        default -> nop();
        }
        send();
    }

    /** MaSmw_Ctrl 0x37: the exclusive part of MaRmdCnv_Ctrl, data from f0 to f7 */
    public void exclusive(byte[] data) {
        int n = data.length;
        if (n == 0 || (data[0] & 0xff) != 0xf0 || (data[n - 1] & 0xff) != 0xf7) return;
        for (int i = 1; i < n - 1; i++) if (data[i] < 0) return;
        if (n == 6 && data[1] == 0x7e && data[2] == 0x7f && data[3] == 9 && data[4] >= 1 && data[4] <= 3) {
            masterVolumeConverter = 0x7f;
            for (int i = 0; i < 16; i++) {
                converter[i] = new ConverterChannel();
            }
            converter[9].bank = 0x7800;
            systemOn();
        } else if (n == 8 && data[1] == 0x7f && data[2] == 0x7f && data[3] == 4 && data[4] == 1) {
            masterVolumeConverter = data[6];
            masterVolume(data[6]);
        } else if (n == 8 && data[1] == 0x7f && data[2] == 0x7f && data[3] == 4 && data[4] == 3) {
            masterFineTuning(data[5] | data[6] << 7);
        } else if (n == 8 && data[1] == 0x7f && data[2] == 0x7f && data[3] == 4 && data[4] == 4) {
            masterCoarseTuning(data[6]);
        } else if (n >= 8 && data[1] == 0x43 && data[2] == 0x79 && data[3] == 0x06 && data[4] == 0x7f) {
            yamaha(data);
        } else {
            nop();
        }
        send();
    }

    /**
     * The exclusives of yamaha a song sends, {@code f0 43 79 06 7f nn ... f7}: the voices and the
     * waves of the song itself, as {@code MaRmdCnv_SetLongMsg} of the MA-3 driver ({@code marmdcnv.c})
     * takes them, which is what the library does here too.
     * <p>
     * only the voices ({@code 01}) and the waves of a wave table voice ({@code 03}) are of the sound
     * source; the rest ({@code 00} max gain, {@code 10} a user event, ...) is the player's.
     */
    private void yamaha(byte[] data) {
        int n = data.length;
        switch (data[5]) {
        case 0x01 -> {
            // 01 mm ll pc dn vt <7 bit voice> f7, the length tells the voice: see setVoice
            if (n < 31) return;
            int bank, program;
            int bankMsb = data[6] & 0x7f, bankLsb = data[7] & 0x7f, pc = data[8] & 0x7f, key = data[9] & 0x7f;
            int type = (data[10] & 0x7f) + 1;
            switch (bankMsb) {
            case 0x7c -> { bank = 1 + bankLsb; program = pc; }
            case 0x7d -> { bank = 129 + pc; program = key; }
            default -> { return; }
            }
            switch (n) {
            case 31 -> { if (type == 2) waveTableVoice(bank, program, decode(data, 11, n - 1)); }
            case 32, 48 -> { if (type == 1) fmVoice(bank, program, decode(data, 11, n - 1)); }
            default -> { }
            }
        }
        case 0x03 -> {
            // 03 id fl <7 bit wave> f7, the wave a wave table voice of the song plays
            if (n < 10) return;
            byte[] wave = decode(data, 8, n - 1);
            int address = ram(wave.length);
            if (address < 0) return;
            sendRamData(address, wave, 0, wave.length);
            wtWave[data[6] & 0x7f] = address;
        }
        default -> {}
        }
        nop();
    }

    /**
     * The 8 bit bytes of a message packed 7 bit ({@code Decode7Enc}): a flag byte holding the bit 7
     * of the seven bytes after it, the first of them in its bit 6.
     */
    private static byte[] decode(byte[] data, int from, int to) {
        byte[] out = new byte[Math.max((to - from) - ((to - from) + 7) / 8, 0)];
        int k = 0;
        for (int i = from; i < to; i += 8) {
            int flags = data[i] & 0xff;
            for (int j = 1; j < 8 && i + j < to && k < out.length; j++) {
                out[k++] = (byte) ((((flags >> (7 - j)) & 1) << 7) | (data[i + j] & 0x7f));
            }
        }
        return out;
    }

    /** @return where {@code size} bytes of a song go in the ram, -1: no room left */
    private int ram(int size) {
        if (size <= 0 || size > ramLeft) return -1;
        int address = ramAddress;
        int step = (size + 1) & ~1;
        ramAddress += step;
        ramLeft -= step;
        return address;
    }

    /** MaDevDrv_SendDirectRamData: an address of 3 bytes, a count of 1 or 2, then the bytes */
    private void sendRamData(int address, byte[] data, int from, int length) {
        send();
        put(address & 0x7f, (address >> 7) & 0x7f, ((address >> 14) & 0x7f) | 0x80);
        if (length < 0x80) {
            put(length | 0x80);
        } else {
            put(length & 0x7f, ((length >> 7) & 0x7f) | 0x80);
        }
        for (int i = 0; i < length; i++) put(data[from + i] & 0xff);
        send();
    }

    /**
     * An FM voice of a song into the ram and the voices of the sequence.
     * <p>
     * The voice of a song is the MA-3 / MA-5 one, 1 byte of its key and then 2 bytes and 7 per
     * operator (2 operators for the algorithms 0 and 1, else 4); the chip's is 2 bytes and 10 per
     * operator, which is the same but for the operator's 5th bits of its rates (a byte of its own
     * here, none of a song's) and its fixed pitch (none of a song's either).
     *
     * @param image the voice as the song has it, 17 or 31 bytes
     */
    private void fmVoice(int bank, int program, byte[] image) {
        int operators = (image[2] & 7) < 2 ? 2 : 4;
        if (image.length < 3 + operators * 7) return;
        byte[] voice = new byte[2 + operators * 10];
        int global1 = image[1] & 0xff, global2 = image[2] & 0xff;
        boolean pan = (global2 & 0x30) != 0;
        voice[0] = (byte) (pan ? global1 : 0x80 | (global1 & 3));
        voice[1] = (byte) ((global2 & 0xdf) | (pan ? 0x20 : 0));
        for (int op = 0; op < operators; op++) {
            int i = 3 + op * 7, o = 2 + op * 10;
            voice[o] = image[i];
            voice[o + 1] = image[i + 1];
            voice[o + 2] = image[i + 2];
            voice[o + 3] = image[i + 3];
            voice[o + 4] = image[i + 4];
            voice[o + 6] = image[i + 6];
            // the multiplier of the chip has no 11, 13 and 14 of its own
            voice[o + 9] = (byte) (MULTIPLIER[(image[i + 5] >> 4) & 0xf] << 4 | image[i + 5] & 0xf);
        }
        register(bank, program, voice, image[0] & 0xff, 0);
    }

    /**
     * A wave table voice of a song into the ram and the voices of the sequence.
     * <p>
     * The voice of a song is 2 bytes of the sampling rate of its wave, 13 bytes of the voice and
     * the id of the wave; the chip's is 14 bytes, the wave being an address in it (a wave of the
     * song, or one of the rom when the id has bit 7).
     *
     * @param image the voice as the song has it, 16 bytes
     */
    private void waveTableVoice(int bank, int program, byte[] image) {
        if (image.length < 16) return;
        int id = image[15] & 0xff;
        // the wave, as an address of words: a wave of the song, or the rom's of the id
        int wave = (id < 0x80 ? wtWave[id] : rom.u16(0x432d60 + (id & 0x7f) * 2)) >> 1;
        if (wave == 0) return;
        byte[] voice = new byte[14];
        int p0 = image[2] & 0xff, p1 = image[3] & 0xff;
        boolean mono = (p1 & 4) != 0;
        boolean pan = mono || (p0 & 1) != 0;
        voice[0] = (byte) (pan ? p0 & 0xf8 : 0x80);
        voice[1] = (byte) ((p1 & 0xc3) | (mono ? 0x10 : 0) | (pan ? 0x20 : 0));
        System.arraycopy(image, 4, voice, 2, 5);
        voice[8] = (byte) (wave >> 8);
        voice[9] = (byte) wave;
        System.arraycopy(image, 11, voice, 10, 4);
        register(bank, program, voice, (image[0] & 0xff) << 8 | (image[1] & 0xff), 1);
    }

    /**
     * MaSndDrv_SetVoice: the voice into the ram and into the voices of the sequence, which a note
     * of the bank finds by {@link #voiceInfo}. A bank and a program which have one already keep it.
     *
     * @param bank 1 ~ 127 a melody bank (the bank select lsb + 1), 129 ~ a drum one (the program + 129)
     * @param program the program of a melody voice, the key of a drum one
     * @param key an fm voice's own key, a wave table one's sampling rate
     * @param type 0: fm, 1: wave table
     */
    private void register(int bank, int program, byte[] voice, int key, int type) {
        if (bank >= 128 + 128 || program > 127) return;
        short[] table = bank < 0x80 ? melody : drum;
        int at = (bank & 0x7f) * 0x80 + program;
        if (table[at] != 0 || voices >= REGISTERED_VOICES) return;
        int address = ram(voice.length);
        if (address < 0) return;
        sendRamData(address, voice, 0, voice.length);
        int index = voices++;
        voiceAddress[index] = address;
        voiceKey[index] = key & 0xffff;
        voiceType[index] = type;
        table[at] = (short) (0x8000 | index);
    }

    // MaCmd

    /** MaCmd_Nop */
    private void nop() {
        put(0xa7, 0x80);
    }

    /** the channel register, which a channel message sets */
    private static int register(int ch) {
        return ch + SEQ * 0x10;
    }

    /** the volume of the channel to the register */
    private int volume(Channel c, int volume) {
        int v = volumeDb[c.expression] + volumeDb[c.volume2] + volumeDb[masterVolume] + volumeDb[masterVolume2] + volumeDb[volume];
        return volumeLevel[Math.min(v, 0xc0)];
    }

    /** MaCmd_GetVoiceInfo: {result, address, key, type (0: fm, 1: wave table)}, result: 0, 1 (split by keys), -1 none */
    private int[] voiceInfo(Channel c, int key) {
        int bank = c.bank, program = c.program;
        int address, voiceKey, type, result = 0;
        // a voice the song registered for the bank comes before the rom's, see #register
        int registered = (bank < 0x80 ? melody[bank * 0x80 + program] : drum[(bank - 0x80) * 0x80 + key]) & 0xffff;
        if ((registered & 0x8000) != 0) {
            int voice = registered & 0x7fff;
            return new int[] { 0, this.voiceAddress[voice], this.voiceKey[voice], this.voiceType[voice] };
        }
        if (bank < 0x80) {
            type = rom.u8(0x42e000 + program);
            int a = rom.u16(0x42de00 + program * 2);
            if (a > 0x7ff) {
                return new int[] { 0, a, rom.u16(0x42df00 + program * 2), type };
            }
            result = 1;
            int i = a * 0x80 + key;
            voiceKey = rom.u16(0x42e080 + i * 2);
            address = rom.u16(0x42e580 + i * 2);
        } else {
            voiceKey = rom.u16(0x42ec80 + key * 2);
            type = rom.u8(0x42ed80 + key);
            address = rom.u16(0x42ea80 + (key + drumSet * 0x80) * 2);
        }
        if (address == 0) return new int[] { -1 };
        return new int[] { result, address, voiceKey, type };
    }

    /** MaCmd_NoteOn */
    private void noteOn(int ch, int key, int velocity) {
        Channel c = channels[ch];
        int[] vi = voiceInfo(c, key);
        if (vi[0] < 0) {
            nop();
            return;
        }
        boolean drum = c.bank >= 0x80;
        int mode = drum ? 2 : c.poly;
        boolean isWt = vi[3] != 0;
        Ma7Dva.SlotInfo info = dva.getSlot(isWt, SEQ, ch, key, mode);
        int slot = info.slot, other = info.other;
        if (isWt) slot |= 0x40; else other |= 0x40;
        boolean again = vi[0] == 1 && info.status == 2;
        int voiceKey;
        if (again) {
            voiceKey = c.key;
        } else {
            c.key = voiceKey = vi[2];
        }
        int block, fnum;
        if (!isWt) {
            int v;
            if (!drum) {
                v = rom.u16(0x42f850 + (key + rom.u8(0x430050 + c.program) * 0x80) * 2);
            } else {
                v = rom.u16(0x42f850 + ((voiceKey & 0x7f) + rom.u8(0x430050 + c.program + 0x80) * 0x80) * 2);
            }
            block = (v >> 7) & 0x3f;
            fnum = v & 0xff;
        } else {
            if (velocityCurve == 1) {
                int t = volumeDb[velocity];
                t = t > 0x17 ? Math.min(t - 0x18, 0xc0) : 0;
                velocity = volumeLevel[t];
            }
            int m = (int) ((((long) voiceKey << 16) / 24000 + 1) >> 1);
            int x;
            if (!drum) {
                x = rom.s32(0x430150 + (key + rom.u8(0x430050 + c.program) * 0x80) * 4) * m;
            } else {
                x = rom.s32(0x430240 + rom.u8(0x430050 + c.program + 0x80) * 0x200) * m;
            }
            if (x < 0) {
                fnum = 0;
                block = 0x28;
            } else if (x <= 0x4000000) {
                fnum = 1;
                block = 0;
            } else {
                int y = (int) (((x & 0xffffffffL) + 0x8000) >>> 16);
                int e = rom.s32(0x431150 + (y >> 10) * 4);
                int v = ((y >>> (e & 0x1f)) & 0x3ff) + e * 0x400;
                if (v == 0) {
                    block = 0;
                    fnum = 1;
                } else {
                    block = (v >> 7) & 0x3f;
                    fnum = v & 0xff;
                }
            }
        }
        int pitch1, pitch2;
        if (drum) {
            if (isWt) {
                pitch1 = 7;
                pitch2 = 0x70;
            } else {
                int v = rom.u16(0x431250 + (vi[2] & 0x7f) * 2);
                pitch1 = (v >> 7) & 0x3f;
                pitch2 = v & 0x7f;
            }
        } else {
            int v = rom.u16(0x431250 + key * 2);
            pitch1 = (v >> 7) & 0x3f;
            pitch2 = v & 0x7f;
        }
        int address = vi[1];
        switch (info.status) {
        case 2 -> {
            put(0x80, slot | 0x80);
            put(0x84, pitch1, pitch2, velocity & 0x7c, block, fnum | 0x80);
            return;
        }
        case 3 -> put(0x80, other | 0x80, 0x8a, 0xa0, 0x80, slot | 0x80);
        default -> put(0x80, slot | 0x80);
        }
        put(0x8a, 0x90);
        put(0x81, (address >> 15) & 3, (address >> 8) & 0x7f, (address >> 1) & 0x7f, pitch1, pitch2, velocity & 0x7c,
                block, fnum & 0x7f, c.register, c.keyControl | 0xc0);
    }

    /** MaCmd_NoteOff */
    private void noteOff(int ch, int key) {
        int slot = dva.releaseSlot(SEQ, ch, key);
        if (slot < 0) return;
        put(0x80, slot | 0x80, 0x8a, 0x80);
    }

    /** MaCmd_ProgramChange */
    private void programChange(int ch, int bank, int program) {
        Channel c = channels[ch];
        c.bank = bank;
        c.program = program & 0x7f;
        if (bank < 0x80) {
            if (c.registerFixed == 0) c.register = register(ch) | 0x40;
        } else {
            c.poly = 1;
            if (c.registerFixed == 0) c.register = register(ch);
        }
    }

    /** MaCmd_ModulationDepth */
    private void modulation(int ch, int depth) {
        if (depth > 4) depth = 0;
        put(0x8b, register(ch) | 0x80, 0x8f, modulationRegister[depth] | 0x80);
    }

    /** MaCmd_ChannelVolume */
    private void channelVolume(int ch, int volume) {
        Channel c = channels[ch];
        c.volume = volume;
        put(0x8b, register(ch), volume(c, volume) & 0x7c | 0x81);
    }

    /** MaCmd_Expression */
    private void expression(int ch, int expression) {
        Channel c = channels[ch];
        c.expression = expression;
        put(0x8b, register(ch), volume(c, c.volume) & 0x7c | 0x81);
    }

    /** MaCmd_Panpot */
    private void panpot(int ch, int pan) {
        channels[ch].pan = pan;
        put(0x8b, register(ch) | 0x80, 0x8d, pan & 0x7c | 0x81);
    }

    /** MaCmd_Hold1 */
    private void hold(int ch, int hold) {
        Channel c = channels[ch];
        c.hold = hold;
        put(0x8b, register(ch) | 0x80, 0x8e, c.hold | 0x80 | c.holdMode << 4);
    }

    /** MaCmd_FilterResonance */
    private void resonance(int ch, int v) {
        put(0x8b, register(ch) | 0x80, 0x92, filterTable[v] & 0x7e | 0x80);
    }

    /** MaCmd_Brightness */
    private void brightness(int ch, int v) {
        put(0x8b, register(ch) | 0x80, 0x93, filterTable[v] | 0x80);
    }

    /** MaCmd_DrySendLevel, MaCmd_ReverbSendLevel, MaCmd_ChorusSendLevel */
    private void send(int ch, int v, int address, int command) {
        if (command == 10 ? !reverb && !chorus : command == 11 ? !reverb : !chorus) {
            nop();
            return;
        }
        Channel c = channels[ch];
        int level = sendTable[v];
        switch (command) {
        case 10 -> c.dry = level;
        case 11 -> c.reverb = level;
        default -> c.chorus = level;
        }
        put(0x8b, register(ch) | 0x80, address, level & 0x7c | 0x80);
    }

    /** MaCmd_BendRange */
    private void bendRange(int ch, int range) {
        channels[ch].bendRange = Math.min(range, 0x18);
    }

    /** the pitch register */
    private void pitch(int ch, int v) {
        put(0x8b, register(ch) | 0x80, 0x90, (v >> 7) & 0x7f, v & 0x7f | 0x80);
    }

    /** the tunings to the channel */
    private int tune(Channel c) {
        int v = c.fine * c.coarse >> 10;
        return v * (masterFine * masterCoarse >> 10) >> 10;
    }

    /** the tune and the bend to the pitch register */
    private static int pitch(Channel c) {
        int v = c.tune & 0xffff;
        if (c.bend != 0x400) v = v * c.bend >> 10;
        return Math.min(v, 0x3fff);
    }

    /** MaCmd_FineTune */
    private void fineTune(int ch, int v) {
        Channel c = channels[ch];
        c.fine = rom.u16(0x42ef50 + ((v >> 4) & 0x3ff) * 2);
        c.tune = tune(c) & 0xffff;
        pitch(ch, pitch(c));
    }

    /** MaCmd_CoarseTune */
    private void coarseTune(int ch, int v) {
        Channel c = channels[ch];
        c.coarse = rom.u16(0x42f750 + (v & 0x7f) * 2);
        c.tune = tune(c) & 0xffff;
        pitch(ch, pitch(c));
    }

    /** MaCmd_PitchBend */
    private void pitchBend(int ch, int v) {
        Channel c = channels[ch];
        c.bend = rom.u16(0x431460 + (((v >> 7) & 0x7f) + c.bendRange * 0x80) * 2);
        int p = c.bend;
        if (c.tune != 0x400) p = Math.min(p * c.tune >> 10, 0x3fff);
        pitch(ch, p);
    }

    /** MaCmd_MasterFineTuning, MaCmd_MasterCoarseTuning */
    private void masterTuning() {
        int m = masterFine * masterCoarse >> 10;
        for (int ch = 0; ch < 16; ch++) {
            Channel c = channels[ch];
            c.tune = ((c.fine * c.coarse >> 10) * m >> 10) & 0xffff;
            pitch(ch, pitch(c));
        }
    }

    /** MaCmd_MasterFineTuning */
    private void masterFineTuning(int v) {
        masterFine = rom.u16(0x42ef50 + ((v >> 4) & 0x3ff) * 2);
        masterTuning();
    }

    /** MaCmd_MasterCoarseTuning */
    private void masterCoarseTuning(int v) {
        masterCoarse = rom.u16(0x42f750 + (v & 0x7f) * 2);
        masterTuning();
    }

    /** MaCmd_MasterVolume */
    private void masterVolume(int v) {
        masterVolume = v & 0x7f;
        for (int ch = 0; ch < 16; ch++) {
            put(0x8b, register(ch), volume(channels[ch], channels[ch].volume) & 0x7c | 0x81);
        }
    }

    /**
     * MaCmd_AllSoundOff, MaCmd_AllNotesOff, MaCmd_MonoModeOn, MaCmd_PolyModeOn
     * @param damp sound off or key off
     */
    private void allOff(int ch, boolean damp, boolean system) {
        Channel c = channels[ch];
        c.monoCount = 0;
        long fm = damp ? dva.releaseAllSlots(false, SEQ, ch) : dva.releaseOnSlots(false, SEQ, ch);
        long wt = damp ? dva.releaseAllSlots(true, SEQ, ch) : dva.releaseOnSlots(true, SEQ, ch);
        for (int i = 0; i < 64; i++) {
            if ((fm >>> i & 1) != 0) put(0x80, i | 0x80, 0x8a, damp ? 0xb0 : 0x80);
        }
        for (int i = 0; i < 64; i++) {
            if ((wt >>> i & 1) != 0) put(0x80, (i + 0x40) | 0x80, 0x8a, damp ? 0xb0 : 0x80);
        }
    }

    /** MaCmd_MonoModeOn, MaCmd_PolyModeOn */
    private void mono(int ch, boolean mono) {
        Channel c = channels[ch];
        if (!mono) {
            c.poly = 1;
        } else if (c.bank < 0x80) {
            c.poly = 0;
        }
        allOff(ch, false, false);
    }

    /** MaCmd_ResetAllControllers */
    private void resetAllControllers(int ch) {
        expression(ch, 0x7f);
        modulation(ch, 0);
        hold(ch, 0);
        pitchBend(ch, 0x2000);
    }

    /** MaCmd_SystemOn */
    private void systemOn() {
        for (int ch = 0; ch < 16; ch++) allOff(ch, true, true);
        // MaCmd_Init
        masterVolume = 0x7f;
        masterVolume2 = 0x4c;
        masterCoarse = 0x400;
        masterFine = 0x400;
        for (int ch = 0; ch < 16; ch++) {
            Channel c = channels[ch];
            c.keyControl = 0;
            c.monoCount = 0;
            c.drumChannel = 0xff;
            c.bank = ch == 9 ? 0x80 : 0;
            c.program = 0;
            c.poly = 1;
            c.volume = 100;
            c.pan = 0x40;
            c.expression = 0x7f;
            c.holdMode = 1;
            c.hold = 0;
            c.bendRange = 2;
            c.bend = 0x400;
            c.tune = 0x400;
            c.coarse = 0x400;
            c.fine = 0x400;
            c.key = 0;
        }
        for (int ch = 0; ch < 16; ch++) {
            Channel c = channels[ch];
            put(0x8b, register(ch), volume(c, c.volume) & 0x7c | 1, 0x41, c.hold | c.holdMode << 4, 0, 8, 0, 0, 0x80);
            if (reverb) put(0x95, 0xc4);
            if (chorus) put(0x96, 0x80);
            if (reverb) put(0x97, 0xfc);
        }
    }
}
