/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import javax.sound.midi.Instrument;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;

import java.util.ArrayList;
import java.util.List;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;


/**
 * YmF262Soundbank.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/02/25 umjammer initial version <br>
 */
public class YmF262Soundbank implements Soundbank {

    /** */
    private final List<Instrument> instruments = new ArrayList<>();

    @Override
    public String getName() {
        return "YmF262Soundbank";
    }

    @Override
    public String getVersion() {
        return MatsuokaSynthesizer.info.getVersion();
    }

    @Override
    public String getVendor() {
        return MatsuokaSynthesizer.info.getVendor();
    }

    @Override
    public String getDescription() {
        return "Soundbank for YMF262";
    }

    @Override
    public SoundbankResource[] getResources() {
        return getInstruments();
    }

    @Override
    public Instrument[] getInstruments() {
        return instruments.toArray(Instrument[]::new);
    }

    @Override
    public Instrument getInstrument(Patch patch) {
        for (Instrument instrument : instruments) {
            if (instrument.getPatch().getProgram() == patch.getProgram() &&
                    instrument.getPatch().getBank() == patch.getBank()) {
                return instrument;
            }
        }
        return null;
    }

    /** */
    public void addInstrument(int bank, int program, String name, Opl3Instrument data) {
        instruments.add(new YmF262Instrument(this, bank, program, name, data));
    }

    /** */
    public static class YmF262Instrument extends Instrument {
        final Opl3Instrument data;
        protected YmF262Instrument(YmF262Soundbank soundbank, int bank, int program, String name, Opl3Instrument data) {
            super(soundbank, new Patch(bank, program), name, Opl3Instrument.class);
            this.data = data;
        }

        @Override
        public Object getData() {
            return data;
        }

        @Override
        public String toString() {
            return """
                 { %s, /* %d: %s */
               { { 0x%02x, 0x%02x, 0x%02x, 0x%02x, 0x%02x, }, /* OP1 */
                 { 0x%02x, 0x%02x, 0x%02x, 0x%02x, 0x%02x, }, /* OP2 */
                 { 0x%02x, 0x%02x, 0x%02x, 0x%02x, 0x%02x, }, /* OP3 */
                 { 0x%02x, 0x%02x, 0x%02x, 0x%02x, 0x%02x, } }, /* OP4 */
               0x%02x, 0x%02x, 0x%02x, 0x%02x
                  },""".formatted(
                    data.getTypeString(), getPatch().getProgram(), getName(),
                    data.op[0].flg_mul, data.op[0].ksl_tl, data.op[0].ar_dr, data.op[0].sl_rr, data.op[0].ws,
                    data.op[1].flg_mul, data.op[1].ksl_tl, data.op[1].ar_dr, data.op[1].sl_rr, data.op[1].ws,
                    data.op[2].flg_mul, data.op[2].ksl_tl, data.op[2].ar_dr, data.op[2].sl_rr, data.op[2].ws,
                    data.op[3].flg_mul, data.op[3].ksl_tl, data.op[3].ar_dr, data.op[3].sl_rr, data.op[3].ws,
                    data.fb_algA, data.fb_algB, data.fix_dur, data.dpitch
            );
        }
    }
}
