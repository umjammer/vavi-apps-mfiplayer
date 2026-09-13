/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests {@link MaRomWaves}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-13 nsano initial version <br>
 */
class MaRomWavesTest {

    /** a 16KB image whose rom kit points at the rom waves, the rest noise */
    private static byte[] image(Random random) {
        byte[] image = new byte[MaRomWaves.ROM_SIZE];
        random.nextBytes(image);
        int i = 0;
        for (int note : MaRomWaves.WAVE_DRUM_NOTES) {
            int address = MaRomWaves.WAVE_ADDRESSES[i++ % MaRomWaves.WAVE_ADDRESSES.length];
            int p = 0x1000 + (note - 24) * 16 + 7;
            image[p] = (byte) (address >> 8);
            image[p + 1] = (byte) address;
        }
        return image;
    }

    @Test
    void image() {
        byte[] image = image(new Random(1));

        byte[][] waves = MaRomWaves.waves(image);

        assertNotNull(waves);
        assertEquals(7, waves.length);
        assertEquals(0x15d6 - 0x1400, waves[0].length);
        assertEquals(0x4000 - 0x34d2, waves[6].length);
        byte[] wave2 = new byte[0x2288 - 0x1ba4];
        System.arraycopy(image, 0x1ba4, wave2, 0, wave2.length);
        assertArrayEquals(wave2, waves[2]);
    }

    /** an image is found inside anything, an emulator dll */
    @Test
    void inside() {
        Random random = new Random(2);
        byte[] data = new byte[0x20000];
        random.nextBytes(data);
        byte[] image = image(random);
        System.arraycopy(image, 0, data, 0x12345, image.length);

        assertEquals(0x12345, MaRomWaves.find(data));
    }

    @Test
    void none() {
        assertNull(MaRomWaves.waves(new byte[0x8000]));
        byte[] noise = new byte[0x8000];
        new Random(3).nextBytes(noise);
        assertNull(MaRomWaves.waves(noise));
        assertNull(MaRomWaves.waves(new byte[16]));
    }

    /** the MA-5 emulator of the "ATS-MA5-SMAF" authoring tool */
    static final Path dll = Path.of("/usr/local/src/MA-3-MegaMod/ringtones/ATS-MA5-SMAF_1.3.3.14/HVTool/M5_EmuHw.dll");

    static boolean exists() {
        return Files.exists(dll);
    }

    /** the waves match the end points the rom kit's own voices have */
    @Test
    @EnabledIf("exists")
    void emulator() throws Exception {
        byte[][] waves = MaRomWaves.waves(Files.readAllBytes(dll));

        assertNotNull(waves);
        // EP of the rom kit voices of wave 0 ~ 6, [sample]
        int[] endPoints = {0x03a9, 0x0b9b, 0x0dc5, 0x04d7, 0x0cfb, 0x12c0, 0x15db};
        for (int i = 0; i < waves.length; i++) {
            assertTrue(waves[i].length * 2 >= endPoints[i], "wave " + i + " holds its end point");
            assertTrue(waves[i].length * 2 - endPoints[i] < 0x100, "wave " + i + " is not longer than its voice");
        }
    }
}
