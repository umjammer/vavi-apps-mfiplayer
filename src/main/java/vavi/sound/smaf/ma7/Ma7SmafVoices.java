/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma7;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import vavi.sound.ma7.Ma7AudioEngine;

import static java.lang.System.getLogger;


/**
 * The voices and the waves a SMAF song brings of its own, in the form the MA-7 takes them.
 * <p>
 * A song sends them as the exclusives of yamaha, {@code 43 79 vv 7f nn ...}, where {@code vv} is
 * the chip they are of: {@code 06} the MA-3 one, whose data is packed 7 bit, and {@code 07} the
 * MA-5 one, which is the very same data 8 bit. The real time midi path of the MA-7 - the one a
 * song is played on here, see {@link vavi.sound.ma7.Ma7Driver} - takes the MA-3 form only, as the
 * MA-3 driver does ({@code MaRmdCnv_SetLongMsg} of {@code marmdcnv.c}), so an MA-5 one is packed
 * into it here.
 * <p>
 * A voice of a song is
 * <pre>
 *  43 79 vv 7f 01 mm ll pc dn vt &lt;voice&gt; f7
 *                 ~~ ~~ ~~ ~~ ~~
 *                 |  |  |  |  +--- 0: fm, 1: wave table, and bit 1: a filter ("AL") before it
 *                 |  |  |  +------ the key of a drum voice
 *                 |  |  +--------- the program of a melody voice, the drum kit of a drum one
 *                 |  +------------ the bank of a melody voice
 *                 +--------------- 0x7c: a melody voice, 0x7d: a drum one
 * </pre>
 * and the wave a wave table voice of it plays {@code 43 79 vv 7f 03 id fl &lt;wave&gt; f7}.
 * <p>
 * A song sets up its voices and its waves before it plays a note, but not always in that order,
 * while the sound source wants the wave of a wave table voice before the voice which plays it: a
 * voice whose wave is still to come waits here until it arrives. So one of these belongs to a
 * song, and the receiver playing the song holds it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano initial version <br>
 * @see vavi.sound.midi.ymf262.YamahaVoices the same messages read for an OPL3
 */
public final class Ma7SmafVoices {

    private static final Logger logger = getLogger(Ma7SmafVoices.class.getName());

    /** the chip an exclusive of a song is of: the MA-3, its data packed 7 bit */
    private static final int MA3 = 0x06;
    /** the MA-5, the same data 8 bit */
    private static final int MA5 = 0x07;

    /** a voice */
    private static final int VOICE = 0x01;
    /** the wave a wave table voice plays */
    private static final int WAVE = 0x03;

    /** an fm voice of a song: its key, 2 global bytes and 7 per operator */
    private static final int FM_2OP = 1 + 2 + 2 * 7;
    private static final int FM_4OP = 1 + 2 + 4 * 7;
    /** a wave table one: 2 bytes of the sampling rate, 13 of the voice and the id of its wave */
    private static final int WAVE_TABLE = 16;
    /** how long a voice message is before its voice */
    private static final int VOICE_HEADER = 10;

    /** where the voices and the waves go, as midi exclusives */
    private final Consumer<byte[]> sink;

    /** the waves of the song registered so far, by id */
    private final boolean[] waves = new boolean[0x80];

    /** the wave table voices waiting for the wave they play, in the order the song sent them */
    private final List<byte[]> waiting = new ArrayList<>();

    /** @param engine the sound source the song plays on */
    public Ma7SmafVoices(Ma7AudioEngine engine) {
        this(engine::yamahaExclusive);
    }

    /** @param sink where an exclusive of the song goes, {@code f0 43 79 06 7f ... f7} */
    Ma7SmafVoices(Consumer<byte[]> sink) {
        this.sink = sink;
    }

    /**
     * Gives the sound source a voice or a wave a SMAF song sends, when the exclusive is one.
     *
     * @param exclusive a smaf exclusive as the song has it, 8 bit and unpacked, {@code 43 ... f7}
     * @return false: it is none of the sound source's, the caller's to log
     */
    public boolean process(byte[] exclusive) {
        if (exclusive.length < 6 || (exclusive[0] & 0xff) != 0x43 || (exclusive[1] & 0xff) != 0x79 ||
                (exclusive[3] & 0xff) != 0x7f) {
            return false;
        }
        int version = exclusive[2] & 0xff;
        if (version != MA3 && version != MA5) {
            return false;
        }
        switch (exclusive[4] & 0xff) {
        case VOICE -> {
            byte[] image = version == MA3 ? decode(exclusive, VOICE_HEADER, exclusive.length - 1)
                    : image(exclusive, exclusive[9] & 0xff);
            if (image == null) {
logger.log(Level.DEBUG, "voice type %02x of %d bytes, not taken".formatted(exclusive[9], exclusive.length - 11));
                return false;
            }
            byte[] ma3 = voice(exclusive, image);
            int wave = wave(exclusive[9] & 0xff, image);
            // the wave of the voice may be one the song has not sent yet
            if (wave >= 0 && !waves[wave]) {
                waiting.add(ma3);
            } else {
                send(ma3);
            }
            return true;
        }
        case WAVE -> {
            byte[] ma3 = version == MA3 ? exclusive : wave(exclusive);
            if (ma3 == null) {
                return false;
            }
            send(ma3);
            waves[ma3[5] & 0x7f] = true;
            // the voices which were waiting for it
            waiting.removeIf(v -> {
                byte[] image = decode(v, VOICE_HEADER, v.length - 1);
                int wave = wave(v[9] & 0xff, image);
                if (wave >= 0 && !waves[wave]) {
                    return false;
                }
                send(v);
                return true;
            });
            return true;
        }
        default -> {
            // the master volume, the panpot of a stream, a user event ...: the player's, not the chip's
            return false;
        }
        }
    }

    /**
     * The id of the wave of the song a voice plays, -1 when it plays none: an fm voice, or a wave
     * table one on a wave of the rom, whose id has bit 7.
     */
    private static int wave(int type, byte[] image) {
        if ((type & 1) == 0 || image.length < WAVE_TABLE) {
            return -1;
        }
        int id = image[WAVE_TABLE - 1] & 0xff;
        return id < 0x80 ? id : -1;
    }

    /**
     * A voice message as the MA-3 one, with the voice packed 7 bit.
     * <p>
     * A voice of the type 2 or 3 has a filter ("AL") before the voice itself, which the sound
     * source has nothing of yet ({@link #image} leaves it): the voice is taken without it, so that
     * the song sounds its own voice rather than one of the rom, the filter being all it loses.
     */
    private static byte[] voice(byte[] exclusive, byte[] image) {
        byte[] ma3 = new byte[VOICE_HEADER + packed(image.length) + 1];
        System.arraycopy(exclusive, 0, ma3, 0, VOICE_HEADER);
        ma3[2] = MA3;
        ma3[9] = (byte) (exclusive[9] & 1); // without the filter the type is the voice's alone
        pack(image, ma3, VOICE_HEADER);
        ma3[ma3.length - 1] = (byte) 0xf7;
        return ma3;
    }

    /**
     * The voice itself of an MA-5 voice message, which is at its end: a wave table voice is
     * {@link #WAVE_TABLE} bytes, an fm one {@link #FM_2OP} or {@link #FM_4OP} by its algorithm.
     *
     * @return null: it is neither, or the message is too short for it
     */
    private static byte[] image(byte[] exclusive, int type) {
        int end = exclusive.length - 1;
        int length;
        if ((type & 1) != 0) {
            length = WAVE_TABLE;
        } else if (end - FM_2OP >= VOICE_HEADER) {
            // the algorithm, the third byte of a voice, tells 2 operators from 4
            length = (exclusive[end - FM_2OP + 2] & 7) < 2 ? FM_2OP : FM_4OP;
        } else {
            return null;
        }
        if (end - length < VOICE_HEADER) {
            return null;
        }
        byte[] image = new byte[length];
        System.arraycopy(exclusive, end - length, image, 0, length);
        return image;
    }

    /** An MA-5 wave message as the MA-3 one, {@code 43 79 06 7f 03 id fl <wave packed 7 bit> f7} */
    private static byte[] wave(byte[] exclusive) {
        if (exclusive.length < 8) {
            return null;
        }
        int from = 7, length = exclusive.length - 1 - from;
        byte[] data = new byte[length];
        System.arraycopy(exclusive, from, data, 0, length);
        byte[] ma3 = new byte[from + packed(length) + 1];
        System.arraycopy(exclusive, 0, ma3, 0, from);
        ma3[2] = MA3;
        pack(data, ma3, from);
        ma3[ma3.length - 1] = (byte) 0xf7;
        return ma3;
    }

    /** how many bytes {@code length} 8 bit ones are packed 7 bit */
    private static int packed(int length) {
        return length + (length + 6) / 7;
    }

    /**
     * Packs 8 bit bytes 7 bit: a flag byte holding the bit 7 of the seven bytes after it, the
     * first of them in its bit 6.
     */
    private static void pack(byte[] data, byte[] out, int at) {
        for (int i = 0; i < data.length; i += 7) {
            int n = Math.min(7, data.length - i), flags = 0;
            for (int j = 0; j < n; j++) {
                flags |= ((data[i + j] >> 7) & 1) << (6 - j);
            }
            out[at++] = (byte) flags;
            for (int j = 0; j < n; j++) {
                out[at++] = (byte) (data[i + j] & 0x7f);
            }
        }
    }

    /** {@link #pack} read backwards */
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

    /** an exclusive of a song to the sound source, as a midi one */
    private void send(byte[] ma3) {
        byte[] sysex = new byte[ma3.length + 1];
        sysex[0] = (byte) 0xf0;
        System.arraycopy(ma3, 0, sysex, 1, ma3.length);
        sink.accept(sysex);
    }
}
