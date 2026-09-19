/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;


/**
 * The 2 timers of the chip (ARM::Timer_*), what the irq status bits 5 and 6 are of. The time
 * is in ms * 1000000 as the library keeps it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Timer {

    /** a timer, 0x30 bytes at 0x483360 */
    private static final class Timer {
        boolean running;    // +0
        boolean oneShot;    // +1
        boolean sequencer;  // +2
        int msSequencer;    // +4
        int msTimer;        // +8
        int count;          // +0xc
        long now;           // +0x10
        long next;          // +0x18
        long period;        // +0x20
        long unit;          // +0x28
    }

    private final Timer[] timers = { new Timer(), new Timer() };

    /** the unit of a tick, what a sequencer's ms or the timer's ms is scaled by */
    private static long unit(Timer t) {
        return t.sequencer ? (t.msSequencer * 0x43dL) & 0xffffffffL : (t.msTimer * 0x90f5L) & 0xffffffffL;
    }

    /** Timer_Control */
    void control(int i, int c, long ms) {
        if (i >= 2) return;
        Timer t = timers[i];
        if ((c & 1) == 0) {
            t.running = false;
            return;
        }
        t.sequencer = ((c >> 2) & 1) != 0;
        t.oneShot = ((c >> 1) & 1) != 0;
        t.unit = unit(t);
        t.period = t.unit * (t.count & 0xffffffffL);
        if (!t.running) {
            t.running = true;
            t.now = ms * 1000000;
            t.next = ms * 1000000 + t.period;
        }
    }

    /** Timer_SetCounter */
    void setCounter(int i, int count) {
        if (i >= 2) return;
        Timer t = timers[i];
        if (count == 0) count = 0x80;
        if (t.count != count) {
            t.count = count;
            if (t.running) {
                t.unit = unit(t);
                t.period = t.unit * (count & 0xffffffffL);
            }
        }
    }

    /** Timer_SetMSTimer */
    void setMsTimer(int i, int ms) {
        if (i >= 2) return;
        Timer t = timers[i];
        if (ms == 0) ms = 0x80;
        if (t.msTimer != ms) {
            t.msTimer = ms;
            if (t.running && !t.sequencer) {
                t.unit = unit(t);
                t.period = t.unit * (t.count & 0xffffffffL);
            }
        }
    }

    /** Timer_SetMSSequencer */
    void setMsSequencer(int i, int ms) {
        if (i >= 2) return;
        Timer t = timers[i];
        if (t.msSequencer != ms) {
            t.msSequencer = ms;
            if (t.running && t.sequencer) {
                t.unit = unit(t);
                t.period = t.unit * (t.count & 0xffffffffL);
            }
        }
    }

    /** Timer_Generate, @return bit 0, 1: the timers which went off */
    int generate(long ms) {
        int fired = 0;
        long now = ms * 1000000;
        for (int i = 0; i < 2; i++) {
            Timer t = timers[i];
            if (t.running) {
                t.now = now;
                if (t.next <= now) {
                    fired |= 1 << i;
                    t.next += t.period;
                    if (t.oneShot) t.running = false;
                }
            }
        }
        return fired;
    }

    /** Timer_GetCounter */
    int counter(int i) {
        if (i >= 2) return 0xff;
        Timer t = timers[i];
        int v;
        if (!t.running) {
            v = t.count & 0xff;
        } else {
            int count = t.count & 0xff;
            int left = t.unit == 0 ? count : (int) ((t.next - t.now) / t.unit) & 0xff;
            v = count <= left ? count : left;
        }
        if ((byte) v < 0) v = 0;
        return v;
    }
}
