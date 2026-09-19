/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;


/**
 * The noise of the chip (ARM::CWnoise), one state for all who take it (a static of the
 * library, {@code m_dRand}), so the order they take it in is what they get.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Noise {

    private int state;

    /** @return 16 bit, unsigned */
    int generate() {
        int s = state;
        state = (((s >>> 31) ^ (s >>> 2)) | (s == 0 ? 1 : 0)) + (s << 1);
        return s >>> 16;
    }
}
