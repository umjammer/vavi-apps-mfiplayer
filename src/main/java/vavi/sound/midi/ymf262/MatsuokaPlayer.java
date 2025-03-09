/*
 * The "Artistic License"
 *
 * Copyright Holder: Claudio Matsuoka <claudio@conectiva.com>
 */

package vavi.sound.midi.ymf262;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import mdsound.chips.YmF262;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;

import static java.lang.System.getLogger;


/**
 * MatsuokaPlayer.
 *
 * @author <a href="claudio@conectiva.com">Claudio Matsuoka</a>
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/02/23 umjammer initial version <br>
 */
public class MatsuokaPlayer {

    private static final Logger logger = getLogger(MatsuokaPlayer.class.getName());

    static final int BUFFER_SIZE = 152;

    static final int SEQUENCER_CHANNELS = 16;
    static final int OPL3_VOICES = 6;

    static final int OPL3_TYPE_2OP = 0;
    static final int OPL3_TYPE_4OP = 1;

    static final int OPL3_REG_OP_FLG_MUL = 0x20;
    static final int OPL3_REG_OP_KSL_TL = 0x40;
    static final int OPL3_REG_OP_AR_DR = 0x60;
    static final int OPL3_REG_OP_SL_RR = 0x80;
    static final int OPL3_REG_OP_WS = 0xe0;

    static final int OPL3_REG_CH_FREQ = 0xa0;
    static final int OPL3_REG_CH_KEY_BLOCK = 0xb0;
    static final int OPL3_REG_FB_ALG = 0xc0;

    static final int OPL3_BASE = 0x220;

    private final YmF262[] chips;

    Soundbank standards;
    Soundbank drums;

    public MatsuokaPlayer() {
        chips = new YmF262[NUM_CHIPS];
        for (int i = 0; i < NUM_CHIPS; i++)
            chips[i] = new YmF262();

        try {
            standards = new SbiSoundbankReader().getSoundbank(MatsuokaPlayer.class.getResource("/opl3/std.o3"));
            opl3_ins = Arrays.stream(standards.getInstruments()).map(SoundbankResource::getData).toArray(Opl3Instrument[]::new);
            drums = new SbiSoundbankReader().getSoundbank(MatsuokaPlayer.class.getResource("/opl3/drums.o3"));
            opl3_drum = Arrays.stream(drums.getInstruments()).map(SoundbankResource::getData).toArray(Opl3Instrument[]::new);
        } catch (InvalidMidiDataException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void _opl3_write(int c, int a, int v) {
        int b = OPL3_BASE;
        if ((a & 0xff00) != 0) b += 2;
//logger.log(Level.TRACE, " opl3: %02x %02x %02x\n", c, a, v);
        chips[c].write(b, a);
        chips[c].write(++b, v);
    }

    static final int NUM_CHIPS = (((SEQUENCER_CHANNELS - 1) / OPL3_VOICES) + 1);

    static int OPL3_CHIP(int c) {
        return c / OPL3_VOICES;
    }

    static int OPL3_CHAN(int c) {
        return c % OPL3_VOICES;
    }

    static int[] opl3_op_mode = new int[SEQUENCER_CHANNELS];

    final Opl3Instrument[] opl3_ins;
    final Opl3Instrument[] opl3_drum;

//#ifdef UNROLL

//    int opl3_write_op(int c, int o, int b, int d) {
//        return fm_set_parameter(OPL3_CHIP(c), OPL3_CHAN(c), o, b, ins.op[o].d);
//    }
//
//    int opl3_write_chan(int c, int b, int d) {
//        return fm_set_parameter(OPL3_CHIP(c), OPL3_CHAN(c), 0, b, d);
//    }

//#else

    /* YMF262 registers */

    static final int[][] opl3_op = {
            {0, 1, 2, 18, 19, 20},
            {3, 4, 5, 21, 22, 23},
            {6, 7, 8, 24, 25, 26},
            {9, 10, 11, 27, 28, 29}
    };

    static final int[] opl3_reg = {
            0x000, 0x001, 0x002, 0x003,
            0x004, 0x005, 0x008, 0x009,
            0x00a, 0x00b, 0x00c, 0x00d,
            0x010, 0x011, 0x012, 0x013,
            0x014, 0x015, 0x100, 0x101,
            0x102, 0x103, 0x104, 0x105,
            0x108, 0x109, 0x10A, 0x10B,
            0x10c, 0x10d, 0x110, 0x111,
            0x112, 0x113, 0x114, 0x115
    };

    /** For regbase 0x20, 0x40, 0x60, 0x80 and 0xe0 */
    static int OPL3_REG_OP(int chn, int o, int base) {
        return opl3_reg[opl3_op[o][chn]] + base;
    }

    static final int[] opl3_chn = {
            0x000, 0x001, 0x002, /* 0x003, 0x004, 0x005, 0x006, 0x007, 0x008, */
            0x100, 0x101, 0x102, /* 0x103, 0x104, 0x105, 0x106, 0x107, 0x108 */
    };

    /* For regbase 0xa0, 0xb0 and 0xc0 */
    static int OPL3_REG_CHN(int chn, int base) {
        return opl3_chn[chn] + (base);
    }

    void opl3_write_op(int c, int o, int b, int d) {
        _opl3_write(OPL3_CHIP(c), OPL3_REG_OP(OPL3_CHAN(c), o, b), d);
    }

    void opl3_write_chan(int c, int b, int d) {
        _opl3_write(OPL3_CHIP(c), OPL3_REG_CHN(OPL3_CHAN(c), b), d);
    }

//#endif

    private void set_type(int c, int t) {

        opl3_op_mode[c] = t;

        for (int i = 0; i < NUM_CHIPS; i++) {
            int j, x;
            for (x = j = 0; j < OPL3_VOICES; j++) {
                x <<= 1;
                if (opl3_op_mode[j] == OPL3_TYPE_4OP)
                    x |= 1;
            }
            _opl3_write(i, 0x104, x);
        }
    }

    private void set_note(int c, int n) {
        int block = n / 12;
        n = n - 24;

        int freq = (int) (66.0 * Math.pow(2.0, 1.0 * n / 12.0));

        int fNum = (freq << (20 - block)) / 49716;

        opl3_write_chan(c, OPL3_REG_CH_FREQ, fNum & 0xff);
        opl3_write_chan(c, OPL3_REG_CH_KEY_BLOCK,
                (1 << 5) | ((block & 0x07) << 2) | ((fNum & 0x300) >> 8));
    }

    private void stop_note(int c) {
        opl3_write_chan(c, OPL3_REG_CH_KEY_BLOCK, 0);
    }

    private void set_ins(int c, int n, int v) {
        boolean is_drum;
        Opl3Instrument ins;

//logger.log(Level.TRACE, "channel %d set instrument %d".formatted(c, n));

        is_drum = ((n & 0x80) == 0x80);
        n &= 0x7f;

        if (is_drum) {
//#ifdef FM
//            return;
//#elif OPL3
logger.log(Level.DEBUG, "drum: %d, %d".formatted(c, n));
            ins = opl3_drum[n];
//#endif
        } else
            ins = opl3_ins[n];

        set_type(c, ins.type);

        for (int i = 0; i < 4; i++) {
            opl3_write_op(c, i, OPL3_REG_OP_FLG_MUL, ins.op[i].flg_mul);
            opl3_write_op(c, i, OPL3_REG_OP_KSL_TL, ins.op[i].ksl_tl);
            opl3_write_op(c, i, OPL3_REG_OP_AR_DR, ins.op[i].ar_dr);
            opl3_write_op(c, i, OPL3_REG_OP_SL_RR, ins.op[i].sl_rr);
            opl3_write_op(c, i, OPL3_REG_OP_WS, ins.op[i].ws);
        }

        opl3_write_chan(c, OPL3_REG_FB_ALG, 0x30 | ins.fb_algA);
        opl3_write_chan(c, OPL3_REG_FB_ALG + 3, 0x30 | ins.fb_algB);

        if (is_drum)
            set_note(c, ins.dpitch != 0 ? ins.dpitch : 60);

//        if (ins.fix_dur != 0)
//            channel[c].timer = ins.fix_dur;
    }

    public void init(float sampleRate) {
//        System.err.println(" dev: FM synthesizer driver by claudio@helllabs.org");
//        System.err.println(" dev: based on the YMF262 FM sound emulator by Jarek Burczynski");
//        logger.log(Level.TRACE, " dev: initializing %d YMF262 chips\n", NUM_CHIPS);
        for (int i = 0; i < NUM_CHIPS; i++)
            chips[i].start(YmF262.EC_DBOPL, 14260547, (int) sampleRate, null);

logger.log(Level.DEBUG, "YMF262: " + NUM_CHIPS);
        for (int i = 0; i < NUM_CHIPS; i++) {
//            logger.log(Level.TRACE, "#%d ".formatted(i));
            chips[i].reset();
            //_opl3_write(i, 0x01, 0x20); // Enable waveform selection
            //_opl3_write(i, 0xbd, 0xc0); // Set tremolo/vibrato depth
        }
        for (int i = 0; i < SEQUENCER_CHANNELS; i++) {
            opl3_op_mode[i] = OPL3_TYPE_4OP;
        }
//        System.err.println();
    }

    public void noteOn(int channel, int program, int noteNumber, int velocity) {
        set_note(channel, noteNumber);
        set_ins(channel, channel == 9 ? 0x80 | noteNumber : program, velocity);
    }

    public void noteOff(int channel) {
        stop_note(channel);
    }

    public int read(int[][] buffer, int len) throws IOException {
        int[][] buf = new int[buffer.length][Math.min(len, 512)];
        for (int[] b : buffer) {
            Arrays.fill(b, 0);
        }
        for (int i = 0; i < NUM_CHIPS; i++) {
            for (int x = 0; x < len / 512; x++) {
                chips[i].update(buf, 512);
                for (int c = 0; c < buffer.length; c++) {
                    for (int j = 0; j < 512; j++) {
                        buffer[c][x * 512 + j] += buf[c][j];
                    }
                }
            }
            chips[i].update(buf, len % 512);
            for (int c = 0; c < buffer.length; c++) {
                for (int j = 0; j < len % 512; j++) {
                    buffer[c][(len / 512) * 512 + j] += buf[c][j];
                }
            }
        }
        return len;
    }

    public void close() throws IOException {
        for (int i = 0; i < NUM_CHIPS; i++)
            chips[i].stop();
    }
}
