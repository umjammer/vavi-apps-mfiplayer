/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.faith;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


/**
 * The elastic band between a producer that makes audio in bursts and a consumer that takes it a
 * few frames at a time.
 * <p>
 * {@link #write} blocks while the queue is full, which is the whole point: it is what holds an
 * emulator that would otherwise run flat out down to the rate its samples are being consumed at,
 * without either side having to consult a clock.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
class PcmQueue {

    private final byte[] buffer;
    private int head;
    private int count;

    /** the producer has said there will be no more */
    private boolean finished;

    /** the consumer has gone away; whatever is still coming is dropped */
    private boolean closed;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    PcmQueue(int capacity) {
        this.buffer = new byte[capacity];
    }

    /** how many bytes are waiting to be read */
    int available() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Blocks until every byte has been taken, or until the consumer closes the queue. */
    void write(byte[] b, int offset, int length) {
        lock.lock();
        try {
            while (length > 0) {
                while (count == buffer.length && !closed) {
                    notFull.awaitUninterruptibly();
                }
                if (closed) {
                    return;
                }
                int tail = (head + count) % buffer.length;
                int n = Math.min(length, Math.min(buffer.length - count, buffer.length - tail));
                System.arraycopy(b, offset, buffer, tail, n);
                count += n;
                offset += n;
                length -= n;
                notEmpty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes up to {@code length} bytes, waiting up to {@code timeoutMillis} for the producer to
     * come up with them.
     *
     * @return how many bytes were actually read; 0 once the producer has finished and the queue
     *         has run dry, or when it did not keep up
     */
    int read(byte[] b, int offset, int length, long timeoutMillis) {
        lock.lock();
        try {
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (count == 0 && !finished && !closed) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    return 0;
                }
                try {
                    //noinspection ResultOfMethodCallIgnored
                    notEmpty.awaitNanos(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
            int read = 0;
            while (read < length && count > 0) {
                int n = Math.min(length - read, Math.min(count, buffer.length - head));
                System.arraycopy(buffer, head, b, offset + read, n);
                head = (head + n) % buffer.length;
                count -= n;
                read += n;
            }
            if (read > 0) {
                notFull.signalAll();
            }
            return read;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until at least {@code bytes} are waiting, so a consumer can let the producer build
     * up a cushion before taking anything at all.
     *
     * @return whether that much is there now
     */
    boolean awaitAtLeast(int bytes, long timeoutMillis) {
        lock.lock();
        try {
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (count < bytes && !finished && !closed) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    return count >= bytes;
                }
                try {
                    //noinspection ResultOfMethodCallIgnored
                    notEmpty.awaitNanos(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return count >= bytes;
                }
            }
            return count >= bytes;
        } finally {
            lock.unlock();
        }
    }

    /** the producer is done; readers drain what is left and then read 0 for ever */
    void finish() {
        lock.lock();
        try {
            finished = true;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** nobody is listening any more; unblocks a producer that is waiting for room */
    void close() {
        lock.lock();
        try {
            closed = true;
            count = 0;
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** has the producer finished and the queue run dry? */
    boolean isDrained() {
        lock.lock();
        try {
            return (finished || closed) && count == 0;
        } finally {
            lock.unlock();
        }
    }
}
