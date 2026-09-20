/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma7;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;

import vavi.sound.mobile.MobileExclusive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Ma7SmafVoicesTest.
 * <p>
 * The voices of a song are the MA-5 ones (8 bit) and the sound source takes the MA-3 ones (packed
 * 7 bit), so what is checked here is that the very same voice comes out, in the order the sound
 * source needs it: the wave of a wave table voice before the voice itself.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano initial version <br>
 */
class Ma7SmafVoicesTest {

    /** what went to the sound source */
    private final List<byte[]> sent = new ArrayList<>();

    private final Ma7SmafVoices voices = new Ma7SmafVoices(sent::add);

    /** an MA-5 exclusive of a song, {@code 43 79 07 7f nn ... f7} */
    private static byte[] ma5(int[] bytes) {
        byte[] exclusive = new byte[4 + bytes.length + 1];
        exclusive[0] = 0x43;
        exclusive[1] = 0x79;
        exclusive[2] = 0x07;
        exclusive[3] = 0x7f;
        for (int i = 0; i < bytes.length; i++) exclusive[4 + i] = (byte) bytes[i];
        exclusive[exclusive.length - 1] = (byte) 0xf7;
        return exclusive;
    }

    /** an fm voice of 2 operators: its key, 2 global bytes and 7 per operator */
    private static int[] fm(int key) {
        return new int[] {key, 0x40, 0x40, 0x9a, 0x0b, 0xf1, 0xd4, 0x00, 0x82, 0x13,
                          0x9c, 0x0d, 0xf3, 0xd6, 0x00, 0x84, 0x15};
    }

    /** a wave table voice: 2 bytes of the sampling rate, 13 of the voice and the id of its wave */
    private static int[] waveTable(int id) {
        return new int[] {0x3e, 0x80, 0x78, 0x00, 0x00, 0xf0, 0xf0, 0x00, 0x00, 0x00, 0x00, 0x08,
                          0x7a, 0x08, 0x7a, id};
    }

    /** the bytes an exclusive of a song carries packed 7 bit, from {@code at} to its {@code f7} */
    private static byte[] decode(byte[] sysex, int at) {
        List<Byte> bytes = new ArrayList<>();
        for (int i = at; i < sysex.length - 1; i += 8) {
            int flags = sysex[i] & 0xff;
            for (int j = 1; j < 8 && i + j < sysex.length - 1; j++) {
                bytes.add((byte) ((((flags >> (7 - j)) & 1) << 7) | (sysex[i + j] & 0x7f)));
            }
        }
        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < out.length; i++) out[i] = bytes.get(i);
        return out;
    }

    private static byte[] bytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    @Test
    void fmVoiceGoesAsTheMa3One() {
        int[] voice = fm(0x40);
        int[] message = new int[6 + voice.length];
        System.arraycopy(new int[] {0x01, 0x7c, 0x05, 0x65, 0x00, 0x00}, 0, message, 0, 6);
        System.arraycopy(voice, 0, message, 6, voice.length);

        assertTrue(voices.process(ma5(message)));
        assertEquals(1, sent.size());
        byte[] sysex = sent.get(0);
        // f0 43 79 06 7f 01 7c 05 65 00 00 <voice packed 7 bit> f7, which is 32 bytes for 2 operators
        assertEquals(32, sysex.length);
        assertArrayEquals(new byte[] {(byte) 0xf0, 0x43, 0x79, 0x06, 0x7f, 0x01, 0x7c, 0x05, 0x65, 0x00, 0x00},
                Arrays.copyOf(sysex, 11));
        assertEquals((byte) 0xf7, sysex[sysex.length - 1]);
        assertArrayEquals(bytes(voice), decode(sysex, 11));
    }

    @Test
    void aFilterBeforeTheVoiceIsLeftAndTheVoiceKept() {
        int[] voice = fm(0x40);
        int[] filter = new int[27];
        Arrays.fill(filter, 0x11);
        int[] message = new int[6 + filter.length + voice.length];
        // the type 2 is an fm voice with a filter ("AL") before it
        System.arraycopy(new int[] {0x01, 0x7c, 0x06, 0x65, 0x00, 0x02}, 0, message, 0, 6);
        System.arraycopy(filter, 0, message, 6, filter.length);
        System.arraycopy(voice, 0, message, 6 + filter.length, voice.length);

        assertTrue(voices.process(ma5(message)));
        byte[] sysex = sent.get(0);
        assertEquals(0x00, sysex[10]); // the type of the voice alone
        assertArrayEquals(bytes(voice), decode(sysex, 11));
    }

    @Test
    void aWaveTableVoiceWaitsForTheWaveOfTheSongItPlays() {
        int[] voice = waveTable(0x02);
        int[] message = new int[6 + voice.length];
        System.arraycopy(new int[] {0x01, 0x7c, 0x04, 0x65, 0x00, 0x01}, 0, message, 0, 6);
        System.arraycopy(voice, 0, message, 6, voice.length);

        assertTrue(voices.process(ma5(message)));
        assertEquals(0, sent.size(), "the wave of the voice has not come yet");

        // another wave than the one it plays leaves it waiting
        assertTrue(voices.process(ma5(new int[] {0x03, 0x01, 0x00, 0x20, 0x21, 0x22})));
        assertEquals(1, sent.size());

        assertTrue(voices.process(ma5(new int[] {0x03, 0x02, 0x00, 0x30, 0x31, 0x32})));
        assertEquals(3, sent.size(), "the wave and then the voice which was waiting for it");
        assertEquals(0x03, sent.get(1)[5], "the wave comes first");
        assertEquals(0x01, sent.get(2)[5]);
        assertArrayEquals(bytes(voice), decode(sent.get(2), 11));
    }

    @Test
    void aWaveTableVoiceOnAWaveOfTheRomGoesAtOnce() {
        int[] voice = waveTable(0x83);
        int[] message = new int[6 + voice.length];
        System.arraycopy(new int[] {0x01, 0x7c, 0x04, 0x65, 0x00, 0x01}, 0, message, 0, 6);
        System.arraycopy(voice, 0, message, 6, voice.length);

        assertTrue(voices.process(ma5(message)));
        assertEquals(1, sent.size());
        assertEquals(31, sent.get(0).length);
    }

    @Test
    void theVolumeOfTheSongGoesToTheSoundSource() {
        assertTrue(voices.process(ma5(new int[] {0x00, 0x6e})));
        assertArrayEquals(new byte[] {(byte) 0xf0, 0x43, 0x79, 0x06, 0x7f, 0x00, 0x6e, (byte) 0xf7}, sent.get(0));
    }

    /** a song does not always end a message with its 0xf7, as "GuitarMan.mmf" does not */
    @Test
    void aMessageWithoutItsEndOfExclusiveIsTakenToo() {
        byte[] exclusive = {0x43, 0x79, 0x07, 0x7f, 0x00, 0x6e};
        assertTrue(voices.process(exclusive));
        assertArrayEquals(new byte[] {(byte) 0xf0, 0x43, 0x79, 0x06, 0x7f, 0x00, 0x6e, (byte) 0xf7}, sent.get(0));
    }

    @Test
    void whatIsNoneOfTheSoundSourceIsLeftToTheCaller() {
        // the panpot of a stream and a user event: the library takes neither
        assertEquals(false, voices.process(ma5(new int[] {0x0b, 0x03, 0x00, 0x40})));
        assertEquals(false, voices.process(ma5(new int[] {0x10, 0x01})));
        assertEquals(0, sent.size());
    }

    static final Path mmf = Path.of("/usr/local/src/MA-3-MegaMod/ringtones/Yamaha MMF demos/GuitarMan.mmf");

    static boolean exists() {
        return Files.exists(mmf);
    }

    /** every voice and wave of a real song reaches the sound source, the waves before the voices */
    @Test
    @EnabledIf("exists")
    void everyVoiceOfASongIsTaken() throws Exception {
        Sequence sequence = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(mmf)));

        int yamaha = 0;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (!(track.get(i).getMessage() instanceof SysexMessage sysex)) continue;
                byte[] data = sysex.getData();
                if (data.length < 2 || data[0] != 0x45 || data[1] != (byte) 0x7f) continue;
                byte[] exclusive = MobileExclusive.unpack(data);
                if (exclusive.length < 5 || (exclusive[0] & 0xff) != 0x43) continue;
                int message = exclusive[4] & 0xff;
                if (message != 0x01 && message != 0x03) continue; // a voice or a wave
                yamaha++;
                assertTrue(voices.process(exclusive), "voice or wave " + yamaha + " of the song");
            }
        }
        assertEquals(18, yamaha, "15 voices and 3 waves");
        assertEquals(yamaha, sent.size(), "all of them, none left waiting");
        // a voice which plays a wave of the song comes after that wave
        boolean[] waves = new boolean[0x80];
        for (byte[] sysex : sent) {
            if ((sysex[5] & 0xff) == 0x03) {
                waves[sysex[6] & 0x7f] = true;
            } else if ((sysex[10] & 1) != 0) {
                byte[] voice = decode(sysex, 11);
                int id = voice[15] & 0xff;
                if (id < 0x80) assertTrue(waves[id], "the wave " + id + " of a voice is registered");
            }
        }
    }
}
