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
import vavi.sound.midi.ymf262.OplInstrument.Opl2Operator;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;


/**
 * YmF262Soundbank.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/02/25 umjammer initial version <br>
 */
public class YmF262Soundbank implements Soundbank {

    /** */
    private final List<Instrument> instruments = new ArrayList<>();

    /** the name of this bank */
    private final String name;

    public YmF262Soundbank() {
        this("YmF262Soundbank");
    }

    /** @param name a bank which is of a file names itself after it, see {@link Vm3SoundbankReader} */
    public YmF262Soundbank(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
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

    /**
     * The instrument a VM35 (MA-3 / MA-5) FM voice becomes, which is the OPL3 registers of
     * it ({@link YamahaVoices#toOpl3Registers}) in the shape this player wants them.
     * <p>
     * Two operators of the four are sounded, the other two are silent ones - a voice which
     * needs all four is two OPL3 channels, see {@code toOpl3Registers}.
     * </p>
     *
     * @see MatsuokaSynthesizer
     */
    static Opl3Instrument toInstrument(VM35FMVoice voice) {
        int[] registers = YamahaVoices.toOpl3Registers(voice);
        Opl3Instrument instrument = new Opl3Instrument();
        // MatsuokaPlayer#set_type reads this as its own OPL3_TYPE_2OP, the two operator
        // channel mode a converted voice wants (and SbiSoundbankReader's FM_PATCH_UNKNOWN,
        // which is the same 0 - the field means one thing to the reader and another to the
        // player)
        instrument.type = 0;
        for (int op = 0; op < instrument.op.length; op++) {
            Opl2Operator operator = new Opl2Operator();
            if (op < 2) {
                operator.flg_mul = registers[op];
                operator.ksl_tl = registers[2 + op];
                operator.ar_dr = registers[4 + op];
                operator.sl_rr = registers[6 + op];
                operator.ws = registers[8 + op];
            } else {
                operator.ksl_tl = 0x3f; // the quietest TL there is, KSL 0
            }
            instrument.op[op] = operator;
        }
        instrument.fb_algA = registers[10]; // the player adds the stereo bits itself
        instrument.fb_algB = 0;
        instrument.fix_dur = 0;
        instrument.dpitch = 0;
        return instrument;
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
