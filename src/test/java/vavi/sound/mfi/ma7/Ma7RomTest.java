/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Ma7RomTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@EnabledIf("soExists")
class Ma7RomTest {

    static boolean soExists() {
        return Ma7Rom.isAvailable();
    }

    @Test
    void readsTables() throws Exception {
        Ma7Rom rom = Ma7Rom.getInstance();
        // the default drum of the converter, bank 0x7800 of channel 9 (MaRmdCnv_Open)
        assertEquals(0x7800, rom.s32(0xfa100));
        // the voice of the gm piano is a wave table one (MaCmd_GetVoiceInfo)
        assertEquals(1, rom.u8(0x42e000));
        // the exclusive groups of the drums: the hi-hats
        assertEquals(1, rom.u8(0x38c250 + 42));
        assertEquals(1, rom.u8(0x38c250 + 46));
    }
}
