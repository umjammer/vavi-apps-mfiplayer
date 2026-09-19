/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Receiver;

import vavi.sound.mfi.rohm.RohmMfiSynthesizer.RohmMfiReceiver;
import vavi.sound.mfi.vavi.MidiContext;
import vavi.sound.mfi.vavi.sequencer.MfiValueExclusive;
import vavi.sound.mfi.vavi.track.MasterVolumeMessage;
import vavi.sound.midi.MidiUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * RohmAudioEngineTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
@EnabledIf("dllExists")
class RohmAudioEngineTest {

    static boolean dllExists() {
        return RohmRom.isAvailable();
    }

    /** @return rms of 16 bit stereo */
    private static double render(RohmAudioEngine engine, double seconds) {
        byte[] block = new byte[100 * 4];
        double sum = 0;
        int samples = 0;
        for (int b = 0; b < seconds * RohmAudioEngine.SAMPLE_RATE / 100; b++) {
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
        RohmAudioEngine engine = new RohmAudioEngine(RohmRom.getInstance(), false);

        assertEquals(0, render(engine, 0.1));

        engine.shortMessage(0xc0, 0, 0);
        engine.shortMessage(0x90, 60, 100);
        double on = render(engine, 0.5);
        assertTrue(on > 100, "rms: " + on);

        engine.shortMessage(0x80, 60, 0);
        render(engine, 3);
        assertEquals(0, render(engine, 0.1));
    }

    /** the mfi bank 0 is the group 0x7d, which is not the melody group */
    @Test
    void mfiBank() throws Exception {
        RohmRom rom = RohmRom.getInstance();

        RohmAudioEngine melody = new RohmAudioEngine(rom, false);
        melody.shortMessage(0xc0, 1, 0);
        melody.shortMessage(0x90, 60, 100);
        double a = render(melody, 0.3);

        RohmAudioEngine bank0 = new RohmAudioEngine(rom, false);
        bank0.exclusive(MfiValueExclusive.message(MfiValueExclusive.BANK, 0, 0).getMessage());
        bank0.shortMessage(0xc0, 1, 0);
        bank0.shortMessage(0x90, 60, 100);
        double b = render(bank0, 0.3);

        RohmAudioEngine bank1 = new RohmAudioEngine(rom, false);
        bank1.exclusive(MfiValueExclusive.message(MfiValueExclusive.BANK, 0, 2).getMessage());
        bank1.shortMessage(0xc0, 1, 0);
        bank1.shortMessage(0x90, 60, 100);
        double c = render(bank1, 0.3);

        assertTrue(a > 100 && b > 100, "a: " + a + ", b: " + b);
        assertNotEquals(a, b);
        // an even bank is the melody group's first half as it is
        assertEquals(a, c);
    }

    /** the listener's volume is a gain after the song's master volume */
    @Test
    void hostVolumeSurvivesSongVolume() throws Exception {
        RohmRom rom = RohmRom.getInstance();
        Receiver receiver;

        RohmAudioEngine full = new RohmAudioEngine(rom, false);
        receiver = new RohmMfiReceiver(full);
        for (MidiEvent e : new MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        full.shortMessage(0x90, 60, 100);
        double loud = render(full, 0.3);

        RohmAudioEngine quiet = new RohmAudioEngine(rom, false);
        receiver = new RohmMfiReceiver(quiet);
        MidiUtil.volume(receiver, 0.2f);
        for (MidiEvent e : new MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        quiet.shortMessage(0x90, 60, 100);
        double soft = render(quiet, 0.3);

        assertEquals(0.2, soft / loud, 0.05, "loud: " + loud + ", soft: " + soft);
    }
}
