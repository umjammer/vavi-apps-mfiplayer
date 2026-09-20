/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;



/**
 * The dsp of the MA-7 (ARM::CDsp, ARM::DSPCONTROL): the effects (ARM::CDsp2, a program of the
 * driver's, {@link Ma7Dsp2}) and the master volume (ARM::CDsp1).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Dsp {

    private final Ma7Chip chip;

    /** the master volume (CDsp1 +0xc4, +0xc8) */
    private int masterLeft, masterRight;
    /** swap left and right (CDsp1 +0x44) */
    private boolean swap;

    /** the effects */
    final Ma7Dsp2 dsp2;

    Ma7Dsp(Ma7Chip chip) {
        this.chip = chip;
        this.dsp2 = new Ma7Dsp2(chip.rom);
    }

    /** DSPCONTROL_ResetDsp */
    void reset(boolean on) {
        dsp2.resetDsp(on ? 1 : 0);
    }

    /** DSPCONTROL_SetDspVoiceReg of the control registers 0x7a ~ 0x7f */
    void setVoiceRegister(int address, int data) {
        if (address == 0x7c || address == 0x7f) setIntermediateRegister(address, data);
    }

    /** the areas of the dsp ram by the intermediate register 0x3a (0x231e10), the rates of the second (0x231e40) */
    private int[] area0Sizes, area1FsRatios;

    /** the intermediate registers 0x2c ~ 0x4c, 0x7c, 0x7f (DSPCONTROL_SetDspVoiceReg) */
    void setIntermediateRegister(int index, int data) {
        int[] regM = chip.regM[0];
        switch (index) {
        case 0x2f -> {
            // CDsp::ClrDspRAM, CDsp2::ClearSubArea does nothing
            regM[0x2f] = data;
            regM[0x3f] = 0;
        }
        case 0x30 -> {
            regM[0x30] = data & 0xc1;
            dsp2.control(data);
        }
        case 0x31 -> regM[0x31] = data & 3;
        case 0x32 -> {
            regM[0x32] = data;
            dsp2.setDspAdr((regM[0x31] & 3) << 8 | data);
        }
        case 0x38 -> {
            regM[0x38] = data;
            if ((regM[0x30] & 1) == 0) {
                dsp2.setDspData(0, regM[0x37] << 8 | data);
            } else {
                dsp2.setDspData(regM[0x33] << 8 | regM[0x34], regM[0x35] << 24 | regM[0x36] << 16 | regM[0x37] << 8 | data);
            }
        }
        case 0x3a -> regM[0x3a] = data & 0x3f;
        case 0x3b -> {
            regM[0x3b] = data & 1;
            if (area0Sizes == null) {
                area0Sizes = chip.rom.ints(0x231e10, 9);
                area1FsRatios = chip.rom.ints(0x231e40, 4);
            }
            int a = (regM[0x3a] >> 2) & 0xf;
            if (a < 9) dsp2.setArea0Size(area0Sizes[a]);
            dsp2.setArea1FsRatio(area1FsRatios[regM[0x3a] & 3]);
            dsp2.setTramMode(regM[0x3b] & 1);
        }
        case 0x3c -> regM[0x3c] = data & 0x83;
        case 0x3d, 0x4c -> {
            regM[index] = data;
            int start = (regM[0x3c] & 3) << 8 | regM[0x3d], end = (regM[0x4b] & 3) << 8 | regM[0x4c];
            if (start <= end) dsp2.setRewriteWindow(start, end);
        }
        case 0x3e -> regM[0x3e] = data & 7;
        case 0x49 -> regM[0x49] = data; // CDsp1::SetEqCoef, the eq is not ported
        case 0x4a -> {
            regM[0x4a] = data & 1;
            swap = (data & 1) != 0;
        }
        case 0x4b -> regM[0x4b] = data & 3;
        case 0x7c -> dsp2.setDspAdr((chip.regC[0x7b] & 7) << 7 | (data & 0x7f));
        case 0x7f -> {
            dsp2.setDspData(0, ((chip.regC[0x7d] & 0xff) << 14 | (chip.regC[0x7e] & 0xff) << 7 | data) & 0xffff);
            dsp2.setDspAdr(((chip.regC[0x7c] & 0x7f) | (chip.regC[0x7b] & 7) << 7) + 1);
        }
        default -> regM[index] = data;
        }
    }

    /** DSPCONTROL_SetMasterVol */
    void setMasterVolume(int left, int right) {
        masterLeft = left;
        masterRight = right;
    }

    /** DSPCONTROL_Generate: CDsp2::ProcDsp2, CDsp1::ProcDsp1 a sample, 16 bits */
    void generate(int n, int[][] bus) {
        dsp2.generate(n, bus);
        int[] l = bus[0], r = bus[1], wl = bus[9], wr = bus[10];
        for (int i = 0; i < n; i++) {
            int right = wr[i] + (int) (((long) masterRight * r[i]) >> 15);
            int left = wl[i] + (int) (((long) masterLeft * l[i]) >> 15);
            if (swap) {
                int t = left;
                left = right;
                right = t;
            }
            l[i] = left;
            r[i] = right;
        }
        for (int i = 0; i < n; i++) {
            l[i] = Math.clamp(l[i], -0x7fff, 0x7fff);
            r[i] = Math.clamp(r[i], -0x7fff, 0x7fff);
        }
    }
}
