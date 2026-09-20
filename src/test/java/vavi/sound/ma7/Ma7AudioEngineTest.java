/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Receiver;

import vavi.sound.mfi.ma7.Ma7MfiSynthesizer.Ma7MfiReceiver;
import vavi.sound.mfi.vavi.MidiContext;
import vavi.sound.mfi.vavi.track.MasterVolumeMessage;
import vavi.sound.midi.MidiUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Ma7AudioEngineTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@EnabledIf("soExists")
class Ma7AudioEngineTest {

    static boolean soExists() {
        return Ma7Rom.isAvailable();
    }

    /** @return rms of 16 bit stereo */
    private static double render(Ma7AudioEngine engine, double seconds) {
        byte[] block = new byte[100 * 4];
        double sum = 0;
        int samples = 0;
        for (int b = 0; b < seconds * Ma7AudioEngine.SAMPLE_RATE / 100; b++) {
            engine.render(block, 100);
            for (int i = 0; i < block.length; i += 2) {
                int v = (short) ((block[i] & 0xff) | (block[i + 1] << 8));
                sum += (double) v * v;
                samples++;
            }
        }
        return Math.sqrt(sum / samples);
    }

    @Test
    void noteSoundsAndDiesAway() throws Exception {
        Ma7AudioEngine engine = new Ma7AudioEngine(Ma7Rom.getInstance(), false);

        assertEquals(0, render(engine, 0.1));

        engine.shortMessage(0xc0, 0, 0);
        engine.shortMessage(0x90, 60, 100);
        double on = render(engine, 0.5);
        assertTrue(on > 100, "rms: " + on);

        engine.shortMessage(0x80, 60, 0);
        render(engine, 3);
        assertEquals(0, render(engine, 0.1));
    }

    /** an odd mfi bank is the second half of the gm programs, 0 and 1 the program 0, as the library */
    @Test
    void mfiBank() throws Exception {
        Ma7Rom rom = Ma7Rom.getInstance();

        Ma7AudioEngine melody = new Ma7AudioEngine(rom, false);
        melody.shortMessage(0xc0, 1, 0);
        melody.shortMessage(0x90, 60, 100);
        double a = render(melody, 0.3);

        Ma7AudioEngine upper = new Ma7AudioEngine(rom, false);
        upper.shortMessage(0xc0, 0x41, 0);
        upper.shortMessage(0x90, 60, 100);
        double b = render(upper, 0.3);

        Ma7AudioEngine bank1 = new Ma7AudioEngine(rom, false);
        bank1.bankChange(0, 3);
        bank1.shortMessage(0xc0, 1, 0);
        bank1.shortMessage(0x90, 60, 100);
        double c = render(bank1, 0.3);

        Ma7AudioEngine bank2 = new Ma7AudioEngine(rom, false);
        bank2.bankChange(0, 2);
        bank2.shortMessage(0xc0, 1, 0);
        bank2.shortMessage(0x90, 60, 100);
        double d = render(bank2, 0.3);

        Ma7AudioEngine piano = new Ma7AudioEngine(rom, false);
        piano.shortMessage(0xc0, 0, 0);
        piano.shortMessage(0x90, 60, 100);
        double e = render(piano, 0.3);

        Ma7AudioEngine bank0 = new Ma7AudioEngine(rom, false);
        bank0.bankChange(0, 0);
        bank0.shortMessage(0xc0, 1, 0);
        bank0.shortMessage(0x90, 60, 100);
        double f = render(bank0, 0.3);

        assertTrue(a > 100 && b > 100, "a: " + a + ", b: " + b);
        assertNotEquals(a, b);
        assertEquals(b, c);
        assertEquals(a, d);
        assertEquals(e, f);
    }

    /** the listener's volume is a gain after the song's master volume */
    @Test
    void hostVolumeSurvivesSongVolume() throws Exception {
        Ma7Rom rom = Ma7Rom.getInstance();
        Receiver receiver;

        Ma7AudioEngine full = new Ma7AudioEngine(rom, false);
        receiver = new Ma7MfiReceiver(full);
        for (MidiEvent e : new MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        full.shortMessage(0x90, 60, 100);
        double loud = render(full, 0.3);

        Ma7AudioEngine quiet = new Ma7AudioEngine(rom, false);
        receiver = new Ma7MfiReceiver(quiet);
        MidiUtil.volume(receiver, 0.2f);
        for (MidiEvent e : new MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        quiet.shortMessage(0x90, 60, 100);
        double soft = render(quiet, 0.3);

        assertEquals(0.2, soft / loud, 0.05, "loud: " + loud + ", soft: " + soft);
    }
}
