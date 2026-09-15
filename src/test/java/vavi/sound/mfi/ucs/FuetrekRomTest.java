/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import java.util.Objects;
import java.util.stream.IntStream;

import vavi.sound.mfi.faith.FaithType4Player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * FuetrekRomTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
@EnabledIf("dllExists")
class FuetrekRomTest {

    static boolean dllExists() {
        return FaithType4Player.isAvailable();
    }

    @Test
    void readsPresetTones() throws Exception {
        FuetrekRom rom = FuetrekRom.getInstance();

        assertArrayEquals(new int[] { 0x14, FuetrekRom.GROUP_DRUM, FuetrekRom.GROUP_MELODY, 0x7d }, rom.groups());
        for (int program = 0; program < 128; program++) {
            assertNotNull(rom.instrument(FuetrekRom.GROUP_MELODY, program), "program " + program);
        }
        assertEquals(47, IntStream.range(0, 128).mapToObj(key -> rom.instrument(FuetrekRom.GROUP_DRUM, key)).filter(Objects::nonNull).count());

        FuetrekRom.Zone zone = rom.instrument(FuetrekRom.GROUP_MELODY, 0).zone(60);
        assertNotNull(zone);
        assertTrue(zone.sampleA.name.startsWith("Piano"), zone.sampleA.toString());
        assertTrue(zone.sampleA.loopStart <= zone.sampleA.loopEnd && zone.sampleA.loopEnd <= zone.sampleA.pcm.length, zone.sampleA.toString());

        assertEquals(12, rom.pitchRatio.length);
        assertEquals(1025, rom.interpolation.length);
        assertEquals(12, rom.curves.length);
    }
}
