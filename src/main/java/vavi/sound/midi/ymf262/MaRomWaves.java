/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.lang.System.getLogger;


/**
 * The preset (rom) waves of the MA-3 / MA-5, what a wave table voice whose {@code RM} bit
 * is set plays.
 * <p>
 * The waves are inside the chip, in the 16KB rom of the MA-3 and at the same place in the
 * MA-5, and nothing here ships them: they are read from a file the user has, named by
 * {@link #ROM_KEY}. That is either the rom image itself ({@link #ROM_SIZE} bytes) or any
 * file which holds one as it is, the {@code M5_EmuHw.dll} MA-5 emulator of the
 * "ATS-MA5-SMAF" authoring tool being one.
 * </p>
 * <pre>
 *  0x0000   (fm wave tables)
 *  0x0800   melody voices, 16 bytes a program        MA_NORMAL_ROM_ADDRESS
 *  0x1000   drum voices, 16 bytes a note of 24 ~ 84   MA_DRUM_ROM_ADDRESS
 *  0x1400   wave 0 ~ 6, 4 bit adpcm                   ma_rom_wave_address
 *  0x4000
 * </pre>
 * <p>
 * An image is told by its drum voices: 21 notes of the rom kit are wave table voices
 * ({@code ma_rom_drum_type}), whose bytes 7 and 8 are the address of their wave - the
 * {@code bBuf[9], bBuf[10]} {@code Set_Voice3} fills in for a voice of a file, a rom voice
 * being a voice image from its byte 2 on.
 * </p>
 * <p>
 * There is no size of a wave anywhere, one ends where the next begins and the last one
 * at the end of the rom; a voice says where to stop with its end point anyway.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-13 nsano initial version <br>
 * @see "MA-3-MegaMod/megagrrl_ymu762 code/firmware/main/YMU762/maresmgr.c"
 * @see "MA-3-MegaMod/megagrrl_ymu762 code/firmware/main/YMU762/madefs.h"
 */
final class MaRomWaves {

    private static final Logger logger = getLogger(MaRomWaves.class.getName());

    /** system property key of the file the rom waves are read from */
    static final String ROM_KEY = "vavi.sound.midi.ymf262.waveTable.rom";

    /** MA_ROM_SIZE */
    static final int ROM_SIZE = 0x4000;

    /** MA_DRUM_ROM_ADDRESS */
    private static final int DRUM_ADDRESS = 0x1000;

    /** MA_MIN_ROM_DRUM */
    private static final int MIN_DRUM = 24;

    /** the size of a rom voice */
    private static final int VOICE_SIZE = 16;

    /** where the wave address is in a rom voice, a voice image from its byte 2 on */
    private static final int VOICE_WAVE_ADDRESS = 9 - 2;

    /** ma_rom_wave_address, MA_MAX_ROM_WAVE of them */
    static final int[] WAVE_ADDRESSES = {0x1400, 0x15d6, 0x1ba4, 0x2288, 0x24f4, 0x2b72, 0x34d2};

    /** the notes ma_rom_drum_type says are wave table voices */
    static final int[] WAVE_DRUM_NOTES = {31, 33, 35, 36, 38, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 55, 57, 59};

    private MaRomWaves() {
    }

    /**
     * Reads the rom waves of the file {@link #ROM_KEY} names.
     *
     * @return null when the property is not set or the file has no rom
     */
    static byte[][] load() {
        String path = System.getProperty(ROM_KEY);
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            byte[][] waves = waves(Files.readAllBytes(Path.of(path)));
            if (waves == null) {
logger.log(Level.WARNING, "no MA rom in " + path);
            } else {
logger.log(Level.DEBUG, "MA rom waves from " + path);
            }
            return waves;
        } catch (IOException e) {
logger.log(Level.WARNING, "MA rom not read: " + path + ", " + e);
            return null;
        }
    }

    /**
     * Cuts the waves out of a rom image or a file which holds one.
     *
     * @return the 4 bit adpcm of wave 0 ~ 6, null when there is no rom image in the data
     */
    static byte[][] waves(byte[] data) {
        int base = find(data);
        if (base < 0) {
            return null;
        }
        byte[][] waves = new byte[WAVE_ADDRESSES.length][];
        for (int i = 0; i < waves.length; i++) {
            int end = i + 1 < WAVE_ADDRESSES.length ? WAVE_ADDRESSES[i + 1] : ROM_SIZE;
            waves[i] = Arrays.copyOfRange(data, base + WAVE_ADDRESSES[i], base + end);
        }
        return waves;
    }

    /** @return the offset a rom image starts at in the data, -1 when there is none */
    static int find(byte[] data) {
        for (int base = 0; base + ROM_SIZE <= data.length; base++) {
            if (isRom(data, base)) {
                return base;
            }
        }
        return -1;
    }

    /** whether every wave table voice of the rom kit points at a rom wave */
    private static boolean isRom(byte[] data, int base) {
        for (int note : WAVE_DRUM_NOTES) {
            int p = base + DRUM_ADDRESS + (note - MIN_DRUM) * VOICE_SIZE + VOICE_WAVE_ADDRESS;
            int address = ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff);
            if (Arrays.binarySearch(WAVE_ADDRESSES, address) < 0) {
                return false;
            }
        }
        return true;
    }
}
