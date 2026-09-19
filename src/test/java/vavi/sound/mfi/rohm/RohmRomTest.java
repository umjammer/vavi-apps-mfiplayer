/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * RohmRomTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
@EnabledIf("dllExists")
class RohmRomTest {

    static boolean dllExists() {
        return RohmRom.isAvailable();
    }

    @Test
    void readsPrograms() throws Exception {
        RohmRom rom = RohmRom.getInstance();

        // the melody group: one layer, from key 9
        for (int p = 0; p < 128; p++) {
            RohmRom.Program program = rom.program(p);
            assertNotNull(program, "program " + p);
            assertEquals(9, program.key, "program " + p);
            assertTrue(program.layers[0] >= 0, "program " + p);
        }
        assertArrayEquals(new int[] { 0, -1 }, rom.program(0).layers);

        // the drums: the key they are played at, the pans and the exclusive groups the dll writes
        assertEquals(35, rom.program(128).key);
        assertEquals(0, rom.program(128).pan);
        assertEquals(1, rom.program(42 - 35 + 128).exclusiveGroup); // closed hi-hat
        assertEquals(1, rom.program(46 - 35 + 128).exclusiveGroup); // open hi-hat
        assertEquals('"' - 0x40, rom.program(41 - 35 + 128).pan);

        // UCS: the programs 228 ~ 235 are the zones 576 ~ 583
        for (int p = 0; p < 8; p++) {
            assertEquals(RohmRom.UCS_ZONE + p, rom.program(228 + p).layers[0]);
        }
        assertNull(rom.program(256));
        assertEquals(RohmRom.REVERB_COUNT, rom.reverbs.length);
        assertEquals(0, rom.reverbs[0].time);
        assertEquals(0x4000, rom.pitch(0));
        assertEquals(0x2000, rom.egLevel(0x80));
    }
}
