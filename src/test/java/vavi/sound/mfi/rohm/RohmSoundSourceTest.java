/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * RohmSoundSourceTest.
 * <p>
 * The checksums are of what {@code rt_synth_2.dll} renders for the same messages, a block of
 * 128 frames at a time, messages sent before the block they are written at, 32 bit little
 * endian stereo as {@code Render()} writes it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
@EnabledIf("dllExists")
class RohmSoundSourceTest {

    static boolean dllExists() {
        return RohmRom.isAvailable();
    }

    /**
     * @param events "block:hex", hex is a channel message, x hex an exclusive, fx hex a reverb preset
     * @return crc32 of what is rendered
     */
    static long render(RohmSoundSource source, int blocks, String events) {
        String[] list = events.isBlank() ? new String[0] : events.trim().split("\\s+");
        int next = 0;
        int[] out = new int[RohmSoundSource.BLOCK * 2];
        ByteBuffer bytes = ByteBuffer.allocate(out.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        CRC32 crc = new CRC32();
        for (int b = 0; b < blocks; b++) {
            while (next < list.length && Integer.parseInt(list[next].split(":")[0]) == b) {
                String hex = list[next++].split(":")[1];
                if (hex.startsWith("fx")) {
                    source.reverb(Integer.parseInt(hex.substring(2), 16));
                } else if (hex.startsWith("x")) {
                    source.exclusive(HexFormat.of().parseHex(hex.substring(1)));
                } else {
                    byte[] m = HexFormat.of().parseHex(hex);
                    source.shortMessage(m[0] & 0xff, m.length > 1 ? m[1] : 0, m.length > 2 ? m[2] : 0);
                }
            }
            source.render(out);
            bytes.clear();
            for (int v : out) bytes.putInt(v);
            crc.update(bytes.array());
        }
        return crc.getValue();
    }

    /** a piano note, as the dll */
    @Test
    void piano() throws Exception {
        RohmSoundSource source = new RohmSoundSource(RohmRom.getInstance());
        assertEquals(0x76610c6aL, render(source, 400, "0:903c64 300:803c00"));
    }

    /** melody, drums, controllers, bend, hold, tunings, gm system on and a reverb, as the dll */
    @Test
    void mixed() throws Exception {
        RohmSoundSource source = new RohmSoundSource(RohmRom.getInstance());
        String events = "0:fx03 0:c030 0:b0075a 0:903c64 0:904064 0:904364 0:c149 0:b1015a 0:914864 2:b90078 2:993064 " +
                "3:992a64 5:992a64 7:992e64 9:99246e 10:e00050 14:e00060 20:e00040 30:803c00 30:804000 30:804300 " +
                "32:b00a10 34:b00a70 40:91487f 60:814800 60:c258 60:b2400a 60:923064 64:823000 70:b2407f " +
                "72:xf07f7f04040045f7 80:923764 90:823700 120:b07b00 125:xf07e7f0901f7 126:903c64 150:803c00";
        assertEquals(0xd55f4e45L, render(source, 400, events));
    }

    /** key 60 of the piano is at 261 Hz, the dll's is 261.1 */
    @Test
    void pitch() throws Exception {
        RohmSoundSource source = new RohmSoundSource(RohmRom.getInstance());
        source.shortMessage(0x90, 69, 100);
        int[] out = new int[RohmSoundSource.BLOCK * 2];
        int blocks = 200;
        double[] left = new double[blocks * RohmSoundSource.BLOCK];
        for (int b = 0; b < blocks; b++) {
            source.render(out);
            for (int i = 0; i < RohmSoundSource.BLOCK; i++) left[b * RohmSoundSource.BLOCK + i] = out[i * 2];
        }
        // zero crossings upwards over the second half
        int crossings = 0, first = -1, last = -1;
        for (int i = left.length / 2; i < left.length - 1; i++) {
            if (left[i] <= 0 && left[i + 1] > 0) {
                if (first < 0) first = i;
                last = i;
                crossings++;
            }
        }
        double frequency = (crossings - 1) * (double) RohmSoundSource.SAMPLE_RATE / (last - first);
        assertEquals(440, frequency, 440 * 0.01, "frequency: " + frequency);
    }

    /** more notes than voices: the least sounding are taken */
    @Test
    void polyphony() throws Exception {
        RohmSoundSource source = new RohmSoundSource(RohmRom.getInstance());
        int[] out = new int[RohmSoundSource.BLOCK * 2];
        for (int i = 0; i < 100; i++) {
            source.shortMessage(0x90 | (i % 8), 30 + i % 60, 100);
            source.render(out);
        }
        assertTrue(source.voices() <= RohmSoundSource.POLYPHONY);
        assertTrue(source.voices() > RohmSoundSource.POLYPHONY / 2, "voices: " + source.voices());
    }

    /** a UCS wave, bank 0x11 program 0 */
    @Test
    void ucs() throws Exception {
        RohmSoundSource source = new RohmSoundSource(RohmRom.getInstance());
        byte[] saw = new byte[64];
        for (int i = 0; i < saw.length; i++) saw[i] = (byte) (i * 4 - 128);
        source.ucsPcm(saw, 0);
        // address (0x20000 / 4), loop start, end, pitch as the rom's piano
        ByteBuffer header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        int rate = 56758508;
        header.putShort((short) 0x8000).putShort((short) 0).putShort((short) saw.length).putShort((short) rate).putShort((short) (rate >> 16));
        source.ucsWaves(header.array(), 0);
        byte[] zone = HexFormat.of().parseHex("f8c02300002c00000000000000808080805a00697f804180c0016c3b19007801");
        source.ucsZones(zone, 0);
        source.shortMessage(0xb0, 0, 0x11);
        source.shortMessage(0xc0, 0, 0);
        source.shortMessage(0x90, 60, 100);
        int[] out = new int[RohmSoundSource.BLOCK * 2];
        int peak = 0;
        for (int b = 0; b < 50; b++) {
            source.render(out);
            for (int v : out) peak = Math.max(peak, Math.abs(v));
        }
        assertTrue(peak > 500, "peak: " + peak);
    }
}
