/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Ma7SoundSourceTest.
 * <p>
 * The checksums are of what {@code libM7_EmuSmw7.so} renders for the same messages (run on an
 * arm64 emulator), the messages sent to a real time midi sequence ({@code MaSmw_Ctrl} 0x36, 0x37)
 * at the sample they are written at, 16 bit little endian stereo of 48 kHz.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@EnabledIf("soExists")
class Ma7SoundSourceTest {

    static boolean soExists() {
        return Ma7Rom.isAvailable();
    }

    /**
     * @param events "sample:hex", hex is a channel message or an exclusive (f0 ... f7)
     * @return crc32 of what is rendered
     */
    static long render(Ma7SoundSource source, int frames, String events) {
        String[] list = events.isBlank() ? new String[0] : events.trim().split("\\s+");
        int next = 0;
        int[] out = new int[4800 * 2];
        ByteBuffer bytes = ByteBuffer.allocate(out.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        CRC32 crc = new CRC32();
        for (int now = 0; now < frames; ) {
            while (next < list.length && Integer.parseInt(list[next].split(":")[0]) == now) {
                byte[] m = HexFormat.of().parseHex(list[next++].split(":")[1]);
                if ((m[0] & 0xff) == 0xf0) {
                    source.exclusive(m);
                } else {
                    source.shortMessage(m[0] & 0xff, m.length > 1 ? m[1] : 0, m.length > 2 ? m[2] : 0);
                }
            }
            int until = next < list.length ? Integer.parseInt(list[next].split(":")[0]) : frames;
            int n = Math.min(until - now, 4800);
            source.render(out, n);
            bytes.clear();
            for (int i = 0; i < n * 2; i++) bytes.putShort((short) out[i]);
            crc.update(bytes.array(), 0, n * 4);
            now += n;
        }
        return crc.getValue();
    }

    /** a piano note, as the library */
    @Test
    void piano() throws Exception {
        Ma7SoundSource source = new Ma7SoundSource(Ma7Rom.getInstance());
        assertEquals(0x2e321670L, render(source, 96000, "0:903c64 24000:803c00"));
    }

    /**
     * fm and wave table melody, drums, volume, pan, modulation, bend with its range, mono,
     * hold, sends, coarse tuning, master volume, all notes off, gm system on, as the library
     */
    @Test
    void mixed() throws Exception {
        Ma7SoundSource source = new Ma7SoundSource(Ma7Rom.getInstance());
        String events = "0:c030 0:b0075a 0:903c64 0:904064 0:904364 0:c149 0:b1015a 0:914864 960:992464 " +
                "1440:992a64 2400:992a64 3360:992e64 4320:99266e 4800:e00050 6720:e00060 9600:e00040 " +
                "12000:b26500 12000:b26400 12000:b2060c 12000:c218 12000:923464 13000:e27f7f 14400:803c00 " +
                "14400:804000 14400:804300 15360:b00a10 16320:b00a70 19200:91487f 24000:b37e01 24000:c350 " +
                "24000:933064 25000:933464 26000:933764 28800:814800 28800:c458 28800:b4407f 28800:943064 " +
                "30720:843000 33600:b44000 34560:f07f7f04040045f7 38400:943764 43200:843700 43200:b15b50 " +
                "43200:b15d40 44000:f07f7f04010050f7 57600:b07b00 60000:f07e7f0901f7 60480:903c64 72000:803c00";
        assertEquals(0xbf700de6L, render(source, 96000, events));
    }

    /**
     * The voices a song registers of its own ({@code 43 79 06 7f 01}) and the wave one of them
     * plays ({@code ... 03}), an fm voice of 2 and of 4 operators, a wave table one and a drum
     * one: the notes sound those and not the voices of the rom, as the library
     */
    @Test
    void songVoices() throws Exception {
        Ma7SoundSource source = new Ma7SoundSource(Ma7Rom.getInstance());
        String events =
                "0:f04379067f0302000700214263042d46071f08695a3b0c55073e171031527334075d760f18792a0b007c452e072041627803244d663f6849703a1b6c351e773070517213547d162f7078590a6b5c250e4f6740610223446d0f065f48291a7b4c0f157e57507112330e741d364f58396a0c4b3c056e47f7 " +
                "0:f04379067f017c01650000063c4040080f741006001430000f700400001107f7 " +
                "0:f04379067f017c02650000063c6042080f741006001430000f700402001107087f641402002438006f50080000210ff7 " +
                "0:f04379067f017c03650001233e0078000070700000000000087a08007a02f7 " +
                "0:f04379067f017d00022400063c4040080f741006001430000f700400001107f7 4800:b0007c 4800:b02001 4800:c065 " +
                "4800:903c64 14400:803c00 14400:904364 24000:804300 24000:b0007c 24000:b02002 24000:c065 24000:903c64 " +
                "33600:803c00 33600:904364 43200:804300 43200:b0007c 43200:b02003 43200:c065 43200:903c64 52800:803c00 " +
                "52800:904364 62400:804300 62400:b9007d 62400:b92000 62400:c902 62400:992464 72000:892400";
        assertEquals(0xf30cc3c8L, render(source, 91200, events));
    }

    /** more notes than slots: the oldest are taken, nothing breaks */
    @Test
    void polyphony() throws Exception {
        Ma7SoundSource source = new Ma7SoundSource(Ma7Rom.getInstance());
        int[] out = new int[Ma7SoundSource.BLOCK * 2];
        int peak = 0;
        for (int i = 0; i < 200; i++) {
            source.shortMessage(0xc0 | (i % 16), i % 128, 0);
            source.shortMessage(0x90 | (i % 16), 30 + i % 60, 100);
            source.render(out, Ma7SoundSource.BLOCK);
            for (int v : out) peak = Math.max(peak, Math.abs(v));
        }
        source.reset();
        assertTrue(peak > 1000, "peak: " + peak);
    }
}
