/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;


/**
 * The yamaha MA-7 as {@code libM7_EmuSmw7.so} is with a real time midi sequence open: midi in,
 * 48 kHz stereo out. The driver ({@link Ma7Driver}) makes packets of the midi, which go to the
 * chip ({@link Ma7Chip}) at once.
 * <p>
 * not thread safe, the caller locks it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public final class Ma7SoundSource {

    /** the rate the library is initialized at here */
    public static final int SAMPLE_RATE = 48000;
    /** a block of the chip, 1 ms */
    public static final int BLOCK = 48;
    public static final int CHANNELS = 16;
    /** 32 fm and 32 wave table slots */
    public static final int POLYPHONY = 64;

    private final Ma7Chip chip;
    private final Ma7Driver driver;

    private final int[] left = new int[BLOCK], right = new int[BLOCK];

    public Ma7SoundSource(Ma7Rom rom) {
        chip = new Ma7Chip(rom, SAMPLE_RATE);
        driver = new Ma7Driver(rom, chip);
    }

    /** @param status a channel message */
    public void shortMessage(int status, int data1, int data2) {
        driver.message(status, data1, data2);
    }

    /**
     * @param data an exclusive, f0 ... f7: gm system on, the universal master volume,
     *            fine and coarse tuning
     */
    public void exclusive(byte[] data) {
        driver.exclusive(data);
    }

    /** all the notes of all the channels cut */
    public void reset() {
        for (int c = 0; c < CHANNELS; c++) {
            driver.message(0xb0 | c, 0x78, 0);
        }
    }

    /**
     * @param lr 16 bit range, left and right interleaved, written
     * @param frames frames to render
     */
    public void render(int[] lr, int frames) {
        for (int f = 0; f < frames; ) {
            int n = Math.min(frames - f, BLOCK);
            chip.generate(left, right, 0, n);
            for (int i = 0; i < n; i++) {
                lr[(f + i) * 2] = left[i];
                lr[(f + i) * 2 + 1] = right[i];
            }
            f += n;
        }
    }
}
