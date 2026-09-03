/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.faith;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import vavi.util.Debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * FaithType4DeviceTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
class FaithType4DeviceTest {

    /** so junit can ask, which it cannot of a method on another class */
    static boolean isAvailable() {
        return FaithType4Device.isAvailable();
    }

    /** ticks a quarter note, and the sequences here are all quarter notes at the default tempo */
    static final int RESOLUTION = 480;

    /** the default tempo, so a quarter note is half a second */
    static final long QUARTER_MILLIS = 500;

    @Test
    @DisplayName("a synthesizer says how late it is, and it is a musical amount of late")
    void testLatency() {
        long micros = FaithType4Device.getLatencyMicros();
        assertTrue(micros > 0 && micros < 500_000, "latency: " + micros + "us");
        assertEquals(44_100, FaithType4Device.audioFormat().getSampleRate());
        assertTrue(FaithType4Device.getMaxPolyphony() >= 48);
    }

    @Test
    @DisplayName("a note is heard about as long after it is sent as the device said it would be")
    @EnabledIf("isAvailable")
    void testTiming() throws Exception {
        FaithType4Device device = new FaithType4Device();
        device.open();
        Clock clock = new Clock(device);
        try {
            clock.start();
            Thread.sleep(300);

            long sentAt = clock.frames();
            device.send(new byte[] {(byte) 0xc0, 0});
            device.send(new byte[] {(byte) 0x90, 60, 110});
            Thread.sleep(1_500);
            device.send(new byte[] {(byte) 0x80, 60, 0});
            Thread.sleep(1_500);

            short[] samples = clock.samples();
            long heardAt = firstLoud(samples, 0);
            assertTrue(heardAt >= 0, "nothing was heard: " + device.getLog());

            // silence before it, bar the one count the emulated card is given to start it playing
            for (long i = 0; i < sentAt; i++) {
                assertTrue(Math.abs(samples[(int) i]) <= 1, "sound at " + i + " before anything was sent");
            }

            long late = heardAt - sentAt;
            long expected = FaithType4Device.getLatencyMicros() * 44_100 / 1_000_000;
Debug.println("heard %d frames (%dms) after it was sent, against a stated %dms"
        .formatted(late, late * 1000 / 44_100, expected * 1000 / 44_100));
            assertTrue(late >= 0, "heard before it was sent");
            // the stated latency plus the block it waits for and whatever the host was doing
            assertTrue(late < expected + 44_100 / 10,
                    "heard %dms after it was sent, %dms was promised".formatted(
                            late * 1000 / 44_100, expected * 1000 / 44_100));

            // and it stops when it is told to
            long released = sentAt + 3 * 44_100 / 2;
            assertTrue(rms(samples, (int) released + 44_100 / 2, 44_100 / 4)
                            < rms(samples, (int) heardAt, 44_100 / 4) / 4,
                    "the note went on after it was released");
            assertEquals(0, device.getProblems().size(), device::toString);
        } finally {
            clock.stop();
            device.close();
        }
    }

    /**
     * The one that says it is a synthesizer.
     * <p>
     * The same notes twice - once handed to {@link FaithType4Player} which knows all of them
     * before it plays any, and once played into {@link FaithType4Device} one at a time as they
     * fall due - and what comes out has to be the same song. If the live one drifted, dropped
     * messages or played them at the wrong block, this is where it would show.
     */
    @Test
    @DisplayName("played live, it is the same song the song player plays")
    @EnabledIf("isAvailable")
    void testMatchesTheSongPlayer() throws Exception {
        Sequence sequence = scale();
        byte[] wave = FaithType4Renderer.render(sequence, 0);
        short[] batch = samplesOf(wave, 44);

        FaithType4Device device = new FaithType4Device();
        device.open();
        Clock clock = new Clock(device);
        short[] live;
        try {
            clock.start();
            Thread.sleep(300);
            play(device, sequence);
            // the tail the renderer leaves after the last message, and room to find the offset in
            Thread.sleep(2_500);
            live = clock.samples();
        } finally {
            clock.stop();
            device.close();
        }

        double[] a = envelope(live), b = envelope(batch);
Debug.println("live %.1fs, batch %.1fs".formatted(live.length / 44100.0, batch.length / 44100.0));
        assertTrue(a.length > b.length, "the live capture is shorter than the song");

        double best = -1;
        int at = -1;
        for (int offset = 0; offset + b.length <= a.length; offset++) {
            double c = correlation(a, offset, b);
            if (c > best) {
                best = c;
                at = offset;
            }
        }
Debug.println("best correlation %.4f, at %dms in".formatted(best, at * WINDOW * 1000 / 44_100));
        assertTrue(best > 0.9, "the live song is not the song the player plays: " + best);
    }

    /** Plays a sequence into a device the way a sequencer would, and returns when it is over. */
    static void play(FaithType4Device device, Sequence sequence) throws InterruptedException {
        List<MidiEvent> events = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                events.add(track.get(i));
            }
        }
        events.sort(java.util.Comparator.comparingLong(MidiEvent::getTick));
        long start = System.nanoTime();
        for (MidiEvent event : events) {
            long due = event.getTick() * QUARTER_MILLIS / RESOLUTION;
            long wait = due - (System.nanoTime() - start) / 1_000_000L;
            if (wait > 0) {
                Thread.sleep(wait);
            }
            device.send(event.getMessage().getMessage());
        }
    }

    /** eight notes, a chord and a bend, which is enough to tell one song from another */
    static Sequence scale() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, RESOLUTION);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 0, 0), 0));
        int[] notes = {60, 62, 64, 65, 67, 69, 71, 72};
        for (int i = 0; i < notes.length; i++) {
            long on = i * (long) RESOLUTION / 2;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, notes[i], 110), on));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, notes[i], 0),
                                    on + RESOLUTION / 3));
        }
        long chord = notes.length * (long) RESOLUTION / 2;
        for (int note : new int[] {60, 64, 67}) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, note, 110), chord));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, note, 0),
                                    chord + RESOLUTION));
        }
        return sequence;
    }

    /** the samples of a RIFF/WAVE, left channel and right interleaved as they stand */
    static short[] samplesOf(byte[] wave, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(wave, offset, wave.length - offset)
                                      .order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[(wave.length - offset) / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }

    /** where the first sample loud enough to be a note is, in frames */
    static long firstLoud(short[] samples, int from) {
        for (int i = from * 2; i < samples.length; i++) {
            if (Math.abs(samples[i]) > 300) {
                return i / 2;
            }
        }
        return -1;
    }

    static double rms(short[] samples, int frame, int frames) {
        long sum = 0;
        int n = 0;
        for (int i = frame * 2; i < Math.min(samples.length, (frame + frames) * 2); i += 2) {
            sum += (long) samples[i] * samples[i];
            n++;
        }
        return n == 0 ? 0 : Math.sqrt(sum / (double) n);
    }

    /** frames to a window of the envelope the two songs are compared as */
    static final int WINDOW = 44_100 / 20;

    /** how loud it was, twenty times a second, which is what survives a note landing a block out */
    static double[] envelope(short[] samples) {
        double[] out = new double[samples.length / 2 / WINDOW];
        for (int i = 0; i < out.length; i++) {
            out[i] = rms(samples, i * WINDOW, WINDOW);
        }
        return out;
    }

    static double correlation(double[] a, int offset, double[] b) {
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < b.length; i++) {
            ab += a[offset + i] * b[i];
            aa += a[offset + i] * a[offset + i];
            bb += b[i] * b[i];
        }
        return aa == 0 || bb == 0 ? 0 : ab / Math.sqrt(aa * bb);
    }

    /**
     * A listener that is not a loudspeaker.
     * <p>
     * The machine is paced by whoever takes its samples, so a test that took them as fast as they
     * came would have the song play at whatever speed the emulator managed and prove nothing
     * about timing. This takes them at 44100 a second by the host's clock instead, which is what
     * a sound card would have done, and keeps them so the test can look at when things happened.
     */
    static class Clock {

        private final FaithType4Device device;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream(1 << 20);
        private final Thread thread;
        private volatile boolean running = true;

        /** frames handed over, which is how much of the song has been "heard" */
        private volatile long frames;

        Clock(FaithType4Device device) {
            this.device = device;
            this.thread = new Thread(this::run, "faith-type4-test-clock");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        long frames() {
            return frames;
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join(3_000);
        }

        short[] samples() {
            return samplesOf(captured.toByteArray(), 0);
        }

        private void run() {
            byte[] buffer = new byte[4096];
            long start = System.nanoTime();
            while (running) {
                long due = (System.nanoTime() - start) * 44_100 / 1_000_000_000L;
                if (frames >= due) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                int want = (int) Math.min(buffer.length / FaithType4Device.FRAME_SIZE, due - frames)
                        * FaithType4Device.FRAME_SIZE;
                int read = device.read(buffer, 0, want);
                if (read <= 0) {
                    return;
                }
                captured.write(buffer, 0, read);
                frames += read / FaithType4Device.FRAME_SIZE;
            }
        }
    }
}
