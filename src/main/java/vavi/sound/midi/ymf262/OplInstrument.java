/*
 * The "Artistic License"
 *
 * Copyright Holder: Claudio Matsuoka <claudio@conectiva.com>
 */

package vavi.sound.midi.ymf262;

import java.util.StringJoiner;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;


/**
 * Sbi.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-02-04 nsano initial version <br>
 */
public class OplInstrument {

    static final int SBI_INS_2OP = 0;
    static final int SBI_INS_4OP = 1;

    static class Opl2Operator {

        int flg_mul;
        int ksl_tl;
        int ar_dr;
        int sl_rr;
        int ws;
    }

    static class Opl3Instrument {

        int type;
        Opl2Operator[] op = new Opl2Operator[4];
        int fb_algA;
        int fb_algB;
        int fix_dur;
        int dpitch;

        public Object getTypeString() {
            return switch (type) {
                case 1 -> "FM_PATCH_OPL2";
                case 2 -> "FM_PATCH_OPL3";
                default -> "FM_PATCH_UNKNOWN";
            };
        }
    }

    static class Opl2Instrument {
        Opl2Operator[] op = new Opl2Operator[2];
        int fb_alg;
        int dpitch;
    }

    @Serdes
    public static class instrument_2op {
        @Element(sequence = 1, value = "unsigned byte")
        int m_flg_mul;
        @Element(sequence = 2, value = "unsigned byte")
        int c_flg_mul;
        @Element(sequence = 3, value = "unsigned byte")
        int m_ksl_tl;
        @Element(sequence = 4, value = "unsigned byte")
        int c_ksl_tl;
        @Element(sequence = 5, value = "unsigned byte")
        int m_ar_dr;
        @Element(sequence = 6, value = "unsigned byte")
        int c_ar_dr;
        @Element(sequence = 7, value = "unsigned byte")
        int m_sl_rr;
        @Element(sequence = 8, value = "unsigned byte")
        int c_sl_rr;
        @Element(sequence = 9, value = "unsigned byte")
        int m_ws;
        @Element(sequence = 10, value = "unsigned byte")
        int c_ws;
        @Element(sequence = 11, value = "unsigned byte")
        int fb_alg;

        @Override public String toString() {
            return new StringJoiner(", ", instrument_2op.class.getSimpleName() + "[", "]")
                    .add("m_flg_mul=%02x".formatted(m_flg_mul))
                    .add("c_flg_mul=%02x".formatted(c_flg_mul))
                    .add("m_ksl_tl=%02x".formatted(m_ksl_tl))
                    .add("c_ksl_tl=%02x".formatted(c_ksl_tl))
                    .add("m_ar_dr=%02x".formatted(m_ar_dr))
                    .add("c_ar_dr=%02x".formatted(c_ar_dr))
                    .add("m_sl_rr=%02x".formatted(m_sl_rr))
                    .add("c_sl_rr=%02x".formatted(c_sl_rr))
                    .add("m_ws=%02x".formatted(m_ws))
                    .add("c_ws=%02x".formatted(c_ws))
                    .add("fb_alg=%02x".formatted(fb_alg))
                    .toString();
        }
    }

    /**
     * @see "https://moddingwiki.shikadi.net/wiki/SBI_Format"
     */
    @Serdes
    public static class sbi {
        @Element(sequence = 1)
        byte[] magic = new byte[4];
        @Element(sequence = 2)
        byte[] name = new byte[25];
        @Element(sequence = 3, value = "unsigned byte")
        int echo_delay;		/* Extended attributes from sbiload */
        @Element(sequence = 4, value = "unsigned byte")
        int echo_atten;
        @Element(sequence = 5, value = "unsigned byte")
        int chorus_spread;
        @Element(sequence = 6, value = "unsigned byte")
        int trans;
        @Element(sequence = 7, value = "unsigned byte")
        int fix_dur;
        @Element(sequence = 8, value = "unsigned byte")
        int modes;
        @Element(sequence = 9, value = "unsigned byte")
        int fix_key;
        @Element(sequence = 10)
        instrument_2op A;
        @Element(sequence = 11)
        instrument_2op B;
    }

    static final int SBI_2OP = 0;
    static final int SBI_4OP = 1;
    static final int SBI_2OP_SIZE = 42;
    static final int SBI_4OP_SIZE = 60;

    /**
     * @see "https://moddingwiki.shikadi.net/wiki/IBK_Format"
     */
    @Serdes
    public static class ibk {
        String name;
        @Element(sequence = 1)
        instrument_2op i;
        @Element(sequence = 2, value = "unsigned byte")
        int percvoc;	/* Percussion voice number */
        @Element(sequence = 3, value = "byte")
        int transpos;		/* Number of notes to transpose timbre */
        @Element(sequence = 4, value = "unsigned byte")
        int dpitch;	/* percussion pitch: MIDI Note 0 - 127 */
        @Element(sequence = 5)
        byte[] rsv = new byte[2];	/* unsused - so far */

        @Override
        public String toString() {
            return new StringJoiner(", ", ibk.class.getSimpleName() + "[", "]")
                    .add("i=" + i)
                    .add("percvoc=%02x".formatted(percvoc))
                    .add("transpos=%02x".formatted(transpos))
                    .add("dpitch=%02x".formatted(dpitch))
                    .add("rsv=[%02x, %02x]".formatted(rsv[0], rsv[1]))
                    .toString();
        }
    }
}
