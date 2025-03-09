/*
 *  ALSA hwdep SBI FM instrument loader
 *  Copyright (c) 2000 Uros Bizjak <uros@kss-loka.si>
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program; if not, write to the Free Software
 *   Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *
 *  Oct. 2007 - Takashi Iwai <tiwai@suse.de>
 *    Changed to use hwdep instead of obsoleted seq-instr interface
 */

package vavi.sound.midi.ymf262;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Soundbank;
import javax.sound.midi.spi.SoundbankReader;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import vavi.sound.SoundUtil;
import vavi.sound.midi.ymf262.OplInstrument.Opl2Operator;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.util.StringUtil;
import vavi.util.serdes.Element;
import vavi.util.serdes.Serdes;
import vavi.util.serdes.Serdes.Util;

import static java.lang.System.getLogger;
import static java.nio.charset.StandardCharsets.US_ASCII;


/**
 * SbiSoundbankReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/03/03 umjammer initial version <br>
 * @see "https://github.com/alsa-project/alsa-tools/tree/master/seq/sbiload"
 */
class SbiSoundbankReader extends SoundbankReader {

    private static final Logger logger = getLogger(SbiSoundbankReader.class.getName());

    @Override
    public Soundbank getSoundbank(URL url) throws InvalidMidiDataException, IOException {
        return getSoundbank(url.openStream());
    }

    @Override
    public Soundbank getSoundbank(InputStream stream) throws InvalidMidiDataException, IOException {
        return getSoundbankInternal(stream);
    }

    @Override
    public Soundbank getSoundbank(File file) throws InvalidMidiDataException, IOException {
        return getSoundbank(new FileInputStream(file));
    }

    static Soundbank getSoundbankInternal(InputStream is) throws IOException {
        sbi_patch[] patches = loadSbis(is);
        YmF262Soundbank soundbank = new YmF262Soundbank();
        for (int i = 0; i < patches.length; i++) {
            soundbank.addInstrument(0, i, patches[i].getName(), patches[i].toOpl3Instrument());
        }
        return soundbank;
    }

    @Serdes
    static class sbi_patch {
        @Element(sequence = 1)
        byte[] key = new byte[4];
        @Element(sequence = 2)
        byte[] name = new byte[25];
        @Element(sequence = 3)
        byte[] extension = new byte[7];
        @Element(sequence = 4)
        byte[] data; // 16, 24

        public int getType() {
            if (Arrays.equals(this.key, "SBI\032".getBytes()) || Arrays.equals(this.key, "2OP\032".getBytes())) {
                return FM_PATCH_OPL2;
            } else if (Arrays.equals(this.key, "4OP\032".getBytes())) {
                return FM_PATCH_OPL3;
            } else {
                return FM_PATCH_UNKNOWN;
            }
        }
        public String getKey() {
            return new String(this.key, 0, 3, US_ASCII);
        }
        public String getName() {
            return new String(this.name, US_ASCII).replace("\u0000", "");
        }
        public Opl3Instrument toOpl3Instrument() {
            Opl3Instrument instrument = new Opl3Instrument();
            instrument.type = getType();
            for (int i = 0; i < (data.length == DATA_LEN_4OP ? 2 : 1); i ++) {
                Opl2Operator operator = new Opl2Operator();
                operator.flg_mul = data[i * 11 + 0] & 0xff;
                operator.ksl_tl = data[i * 11 + 2] & 0xff;
                operator.ar_dr = data[i * 11 + 4] & 0xff;
                operator.sl_rr = data[i * 11 + 6] & 0xff;
                operator.ws = data[i * 11 + 8] & 0xff;
                instrument.op[i * 2 + 0] = operator;
                operator = new Opl2Operator();
                operator.flg_mul = data[i * 11 + 1] & 0xff;
                operator.ksl_tl = data[i * 11 + 3] & 0xff;
                operator.ar_dr = data[i * 11 + 5] & 0xff;
                operator.sl_rr = data[i * 11 + 7] & 0xff;
                operator.ws = data[i * 11 + 9] & 0xff;
                instrument.op[i * 2 + 1] = operator;
                if (i == 0) instrument.fb_algA = data[i * 11 + 10] & 0xff;
                else instrument.fb_algB = data[i * 11 + 10] & 0xff;
            }
            instrument.fix_dur = extension[FIX_DUR] & 0xff;
            instrument.dpitch = extension[FIX_KEY] & 0xff;
            return instrument;
        }
    }

    static final int DATA_LEN_2OP = 16;
    static final int DATA_LEN_4OP = 24;

    /* offsets for SBI params */
    static final int AM_VIB = 0;
    static final int KSL_LEVEL = 2;
    static final int ATTACK_DECAY = 4;
    static final int SUSTAIN_RELEASE = 6;
    static final int WAVE_SELECT = 8;

    /* offset for SBI instrument */
    static final int CONNECTION = 10;
    static final int OFFSET_4OP = 11;

    /* offsets for SBI extensions */
    static final int ECHO_DELAY = 0;
    static final int ECHO_ATTEN = 1;
    static final int CHORUS_SPREAD = 2;
    static final int TRNSPS = 3;
    static final int FIX_DUR = 4;
    static final int MODES = 5;
    static final int FIX_KEY = 6;

    static final int FM_PATCH_UNKNOWN = 0;
    static final int FM_PATCH_OPL2 = 1;
    static final int FM_PATCH_OPL3 = 2;

    /* Default file type */
    static int file_type = FM_PATCH_UNKNOWN;

    /* Default verbose level */
    static boolean verbose = true;

    /*
     * Show instrument FM operators
     */
    static void show_op(SbiSoundbankReader.sbi_patch inst) {
        int i = 0;
        int ofs = 0;

        int type = inst.getType();
        if (verbose && type == FM_PATCH_UNKNOWN)
            System.err.printf ("%s: wrong instrument key!\n", new String(inst.key, US_ASCII));

        do {
            System.err.printf("%s[%d]: %s ----\n", i > 1 ? "   " : inst.getKey(), i, inst.getName());
            byte val = inst.data[AM_VIB + ofs];
            System.err.printf("  OP%d: flags: %s %s %s %s", i,
                    (val & (1 << 7)) != 0 ? "AM" : "  ",
                    (val & (1 << 6)) != 0 ? "VIB" : "   ",
                    (val & (1 << 5)) != 0 ? "EGT" : "   ",
                    (val & (1 << 4)) != 0 ? "KSR" : "   ");
            val = inst.data[AM_VIB + ofs + 1];
            System.err.printf("    OP%d: flags: %s %s %s %s\n", i + 1,
                    (val & (1 << 7)) != 0 ? "AM" : "  ",
                    (val & (1 << 6)) != 0 ? "VIB" : "   ",
                    (val & (1 << 5)) != 0 ? "EGT" : "   ",
                    (val & (1 << 4)) != 0 ? "KSR" : "");
            val = inst.data[AM_VIB + ofs];
            System.err.printf("  OP%d: MULT = 0x%x", i, val & 0x0f);
            val = inst.data[AM_VIB + ofs + 1];
            System.err.printf("               OP%d: MULT = 0x%x\n", i + 1, val & 0x0f);

            val = inst.data[KSL_LEVEL + ofs];
            System.err.printf("  OP%d: KSL  = 0x%x  TL = 0x%02x", i,
                    (val >> 6) & 0x03, val & 0x3f);
            val = inst.data[KSL_LEVEL + ofs + 1];
            System.err.printf("    OP%d: KSL  = 0x%x  TL = 0x%02x\n", i + 1,
                    (val >> 6) & 0x03, val & 0x3f);
            val = inst.data[ATTACK_DECAY + ofs];
            System.err.printf("  OP%d: AR   = 0x%x  DL = 0x%x", i,
                    (val >> 4) & 0x0f, val & 0x0f);
            val = inst.data[ATTACK_DECAY + ofs + 1];
            System.err.printf("     OP%d: AR   = 0x%x  DL = 0x%x\n", i + 1,
                    (val >> 4) & 0x0f, val & 0x0f);
            val = inst.data[SUSTAIN_RELEASE + ofs];
            System.err.printf("  OP%d: SL   = 0x%x  RR = 0x%x", i,
                    (val >> 4) & 0x0f, val & 0x0f);
            val = inst.data[SUSTAIN_RELEASE + ofs + 1];
            System.err.printf("     OP%d: SL   = 0x%x  RR = 0x%x\n", i + 1,
                    (val >> 4) & 0x0f, val & 0x0f);
            val = inst.data[WAVE_SELECT + ofs];
            System.err.printf("  OP%d: WS   = 0x%x", i, val & 0x07);
            val = inst.data[WAVE_SELECT + ofs + 1];
            System.err.printf("               OP%d: WS   = 0x%x\n", i + 1, val & 0x07);
            val = inst.data[CONNECTION + ofs];
            System.err.printf(" FB = 0x%x,  %s\n", (val >> 1) & 0x07,
                    (val & (1 << 0)) != 0 ? "parallel" : "serial");
            i += 2;
            ofs += OFFSET_4OP;
        } while (i == (type == FM_PATCH_OPL3 ? 1 : 0) << 1);

        System.err.printf("""
                         Extended data:
                          ED = %3d  EA = %3d  CS = %3d  TR = %3d
                          FD = %3d  MO = %3d  FK = %3d
                        """,
                inst.extension[ECHO_DELAY], inst.extension[ECHO_ATTEN],
                inst.extension[CHORUS_SPREAD], inst.extension[TRNSPS],
                inst.extension[FIX_DUR], inst.extension[MODES],
                inst.extension[FIX_KEY]);
    }

    /**
     * @param is mark must be supported
     */
    static sbi_patch[] loadSbis(InputStream is) throws IOException {
        is.mark(4);
        byte[] magic = is.readNBytes(4);
        is.reset();

        int len;
        if (Arrays.equals(magic, "SBI\032".getBytes()) || Arrays.equals(magic, "2OP\032".getBytes())) {
            len = DATA_LEN_2OP;
        } else if (Arrays.equals(magic, "4OP\032".getBytes())) {
            len = DATA_LEN_4OP;
        } else {
            // drums.o3 filled 00 until instruments number 35
logger.log(Level.DEBUG, "wrong instrument key: " + StringUtil.getDump(magic));
            URI uri = SoundUtil.getSource(is);
            if (uri != null) {
                if (uri.toString().endsWith(".o3")) {
                    len = DATA_LEN_4OP;
                } else if (uri.toString().endsWith(".sb")) {
                    len = DATA_LEN_2OP;
                } else {
                    throw new IllegalArgumentException("cannot determine file type: " + uri);
                }
            } else {
                throw new IllegalArgumentException("cannot determine file type: " + StringUtil.getDump(magic));
            }
        }

        List<sbi_patch> l = new ArrayList<>();
        while (is.available() > 0) {
            try {
                sbi_patch sbi = new sbi_patch();
                sbi.data = new byte[len];
                Util.deserialize(is, sbi);
                l.add(sbi);
//                show_op(sbi);
            } catch (EOFException e) {
logger.log(Level.TRACE, "size: " + l.size() + ", rest: " + is.available());
                break;
            }
        }

        return l.toArray(sbi_patch[]::new);
    }
}
