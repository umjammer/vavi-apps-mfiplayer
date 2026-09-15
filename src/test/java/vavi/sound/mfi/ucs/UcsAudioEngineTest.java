/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import vavi.sound.mfi.faith.FaithType4Player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * UcsAudioEngineTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
@EnabledIf("dllExists")
class UcsAudioEngineTest {

    static boolean dllExists() {
        return FaithType4Player.isAvailable();
    }

    /** @return rms of 16 bit stereo */
    private static double render(UcsAudioEngine engine, double seconds) {
        byte[] block = new byte[128 * 4];
        double sum = 0;
        int samples = 0;
        for (int b = 0; b < seconds * UcsAudioEngine.SAMPLE_RATE / 128; b++) {
            engine.render(block, 128);
            for (int i = 0; i < block.length; i += 2) {
                int v = (short) ((block[i] & 0xff) | (block[i + 1] << 8));
                sum += (double) v * v;
                samples++;
            }
        }
        return Math.sqrt(sum / samples);
    }

    @Test
    void presetNoteSoundsAndDiesAway() throws Exception {
        UcsSequencer.waveBank().clear();
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);

        assertEquals(0, render(engine, 0.1));

        engine.programChange(0, 0);
        engine.noteOn(0, 60, 100);
        double on = render(engine, 0.5);
        assertTrue(on > 100, "rms: " + on);

        engine.noteOff(0, 60);
        render(engine, 3);
        assertEquals(0, render(engine, 0.1));
    }

    @Test
    void drumSounds() throws Exception {
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);

        engine.noteOn(9, 36, 100);
        double on = render(engine, 0.2);
        assertTrue(on > 100, "rms: " + on);
    }
}
