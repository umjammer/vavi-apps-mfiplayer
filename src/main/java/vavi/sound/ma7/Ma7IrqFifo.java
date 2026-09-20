/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;


/**
 * The fifo of the software irqs (ARM::SIrqFifo_*), 65 words, read a byte at a time.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7IrqFifo {

    private final int[] data = new int[0x41];
    private int write, read;
    private boolean low;

    void reset() {
        write = 0;
        read = 0;
        low = false;
    }

    /** @return 0x40 when it is full, which goes into the status */
    int set(int v) {
        v &= 0xffff;
        int next = write + 1;
        if (next < 0x41) {
            if (next != read) {
                data[write] = v;
                write = next;
                return 0;
            }
            if (write == 0) {
                data[0x40] = v;
                return 0x40;
            }
        } else if (read != 0) {
            data[write] = v;
            write = 0;
            return 0;
        }
        data[write - 1] = v;
        return 0x40;
    }

    /** the high byte, then the low byte */
    int get() {
        if (write == read) return 0;
        int v = data[read];
        if (low) {
            read++;
            if (read > 0x40) read = 0;
            low = false;
            return v & 0xff;
        }
        low = true;
        return v >> 8;
    }

    /** bit 0: empty, bit 1: full */
    int status() {
        int next = write + 1;
        if (next > 0x40) next = 0;
        return (read == next ? 2 : 0) | (write == read ? 1 : 0);
    }
}
