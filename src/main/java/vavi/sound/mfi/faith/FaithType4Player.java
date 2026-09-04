/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.faith;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import javax.sound.midi.Sequence;

import jdos.api.AudioSink;
import jdos.api.JDosBox;
import jdos.api.StdioSink;

import static java.lang.System.getLogger;


/**
 * Plays a sequence with the Type 4 synthesizer out of Faith's "Ring Tone Authoring Tool" - the
 * fuetrek voice engine an MFi phone had in it - by running the synthesizer on an emulated PC and
 * taking what it writes to its {@code waveOut} device.
 * <p>
 * <b>Why an emulated PC.</b> {@code rt_synth_4.dll} is a 32 bit x86 Windows dll from 2003 and
 * there is no source and no other build of it. Calling it through jna means a 32 bit jvm, which
 * means Windows, which is the one place nobody here is; and the machine this runs on is arm.
 * jdosbox brings its own cpu and its own win32, so the dll runs where it is, and the only thing
 * that has to exist beside it is a program small enough to write - which is {@code rts4c.exe},
 * carried in the jar beside its source, and the command that builds it is at the top of
 * {@code /vavi/sound/mfi/faith/rts4c.c}.
 * <p>
 * <b>What paces it.</b> The dll has no clock, so {@code rts4c} gives it one: it hands the
 * emulated sound card a buffer and cannot fill the next until the card has given one back. So
 * the card has to take its samples at the rate they are meant to be heard at, no faster - which
 * is exactly what happens when {@link PcmQueue#write} blocks until {@link #read} has made room.
 * Let it take them as fast as the emulator can make them and the song comes out at the wrong
 * speed and finishes long before it is over.
 * <p>
 * <b>What starts it.</b> The song begins as soon as there are {@link #CUSHION_SECONDS} of it in
 * hand, not when the last bar has been synthesized. The emulated PC renders about twice as fast
 * as the song plays, so that is a second or two, and the cushion is what a passage it renders
 * more slowly than that is taken out of.
 * <p>
 * <b>What it needs.</b> {@code rt_synth_4.dll}, out of the authoring tool's {@code Tools}
 * directory - {@code -Dvavi.sound.mfi.faith.path=<dir>}, which defaults to where wine would have
 * put it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
public class FaithType4Player {

    private static final Logger logger = getLogger(FaithType4Player.class.getName());

    /** where the authoring tool's {@code Tools} directory is */
    public static final String PATH_KEY = "vavi.sound.mfi.faith.path";

    /** the dll itself, which is the whole of what is taken from that directory */
    static final String DLL = "rt_synth_4.dll";

    /** the front end for the dll that runs on the emulated PC, carried in the jar beside its source */
    static final String RTS4C = "/vavi/sound/mfi/faith/rts4c.exe";

    /** what the dll plays at, after the resampler it puts its own 32000Hz through */
    public static final int SAMPLE_RATE = 44_100;

    public static final int CHANNELS = 2;

    /** stereo, sixteen bits */
    public static final int FRAME_SIZE = 4;

    /** how much audio the queue can hold, which only has to be more than the cushion wants */
    private static final double QUEUE_SECONDS = 20;

    /**
     * How much audio has to be in hand before the first sample is handed over.
     * <p>
     * A song is not evenly hard to synthesize, so the listener starts a little behind the
     * emulator rather than level with it. This costs nothing in total - the emulator is what
     * everything is waiting for either way - it only decides whether the waiting is silence
     * before the music or stuttering during it.
     */
    private static final double CUSHION_SECONDS =
            Double.parseDouble(System.getProperty("vavi.sound.mfi.faith.cushion", "2"));

    /** how long the emulated player is given to make a sound at all before we give up on it */
    private static final long SILENCE_LIMIT_SECONDS = 60;

    /** how much memory the emulated PC gets, in megabytes; a synthesizer and a buffer need little */
    private static final String MEMORY = System.getProperty("vavi.sound.mfi.faith.memory", "32");

    /**
     * Which cpu core the emulated PC gets.
     * <p>
     * The recompiling one, because this is a synthesizer: it is the same few thousand
     * instructions over and over for as long as the song is, which is the case a recompiler is
     * for. It synthesizes about seven times as fast as the interpreter does, and to the sample
     * the same audio - which is the difference between comfortably ahead of the song and hopeless.
     */
    private static final String CORE = System.getProperty("vavi.sound.mfi.faith.core", "dynamic");

    private final Sequence sequence;

    /** how much of the sequence to play, or zero for all of it [ms] */
    private final long millis;

    private Path work;

    private JDosBox dosbox;

    private PcmQueue queue;

    /** what the program on the emulated PC wrote, one line to an entry */
    private final List<String> log = new CopyOnWriteArrayList<>();

    /** set once the emulated player has produced a sample that is not silence */
    private volatile boolean sounding;

    /** set when the emulated player was given long enough to make a sound and never did */
    private volatile boolean gaveUp;

    /** set once the cushion has been built and the song may start */
    private volatile boolean primed;

    /** every frame the emulated player has produced */
    private volatile long producedFrames;

    /** how much digital silence was dropped waiting for {@link #sounding} */
    private long droppedBytes;

    /** frames handed to the caller, which is what has been heard bar the output buffer */
    private volatile long consumedFrames;

    public FaithType4Player(Sequence sequence, long millis) {
        this.sequence = sequence;
        this.millis = millis;
    }

    /** the authoring tool's {@code Tools} directory, which is where the dll lives */
    public static File toolsDirectory() {
        return new File(System.getProperty(PATH_KEY, System.getProperty("user.home")
                + "/.wine/drive_c/Program Files (x86)/Faith/Ring Tone Authoring Tool/Tools"));
    }

    /** is there a Type 4 synthesizer to play with? */
    public static boolean isAvailable() {
        return new File(toolsDirectory(), DLL).exists();
    }

    /** what the program on the emulated PC had to say, in the order it said it */
    public List<String> getLog() {
        return List.copyOf(log);
    }

    /** the lines that say why nothing came out */
    public List<String> getProblems() {
        return log.stream().filter(l -> l.startsWith("rts4c: error")).toList();
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    public int getChannels() {
        return CHANNELS;
    }

    /** has the emulated player made a sound yet? */
    public boolean isSounding() {
        return sounding;
    }

    /** how much audio is queued ahead of the listener - the cushion, in seconds */
    public double getCushionSeconds() {
        return queue == null ? 0 : queue.available() / (double) (SAMPLE_RATE * FRAME_SIZE);
    }

    /** frames handed over so far */
    public long getConsumedFrames() {
        return consumedFrames;
    }

    /** what the emulated player has been up to, for a log line or a test */
    public String getStatistics() {
        return "%.1fs produced, %.1fs of it the silence it starts with".formatted(
                producedFrames / (double) SAMPLE_RATE,
                droppedBytes / (double) (SAMPLE_RATE * FRAME_SIZE));
    }

    /** Boots the machine and returns; the audio turns up at {@link #read} in its own time. */
    public void start() throws IOException {
        if (!isAvailable()) {
            throw new IOException("no " + DLL + " under " + toolsDirectory()
                    + "; set -D" + PATH_KEY + "=<dir>");
        }

        // the win32 layer only ever knows one drive, so the program, the dll and the song have
        // to sit on it together
        work = Files.createTempDirectory("vavi-faith-type4-");
        try (InputStream in = FaithType4Player.class.getResourceAsStream(RTS4C)) {
            if (in == null) {
                throw new IOException("no " + RTS4C + " in the jar");
            }
            Files.write(work.resolve("rts4c.exe"), in.readAllBytes());
        }
        Files.copy(toolsDirectory().toPath().resolve(DLL), work.resolve(DLL));
        if (FaithType4Renderer.writeEvents(sequence, work.resolve("song.rt4"), millis) == 0) {
            throw new IOException("nothing in this sequence the Type 4 synthesizer can play");
        }

        queue = new PcmQueue((int) (SAMPLE_RATE * FRAME_SIZE * QUEUE_SECONDS));

        // there is nothing to draw and nobody to look at it
        if (System.getProperty("jdos.novideo") == null) {
            System.setProperty("jdos.novideo", "true");
        }

        dosbox = new JDosBox()
                .arg("-m", MEMORY)
                .mount('c', work.toFile())
                .command("c:")
                // absolute paths: the win32 layer's idea of where it is need not be the drive's
                .command("rts4c.exe c:\\song.rt4")
                // nothing on the machine's own mixer is wanted, and not opening a line for it
                // keeps it from fighting the player for the host's audio device
                .set("mixer", "nosound", "true")
                .set("midi", "mididevice", "none")
                // the emulated PC's only job is to synthesize faster than the song is heard, so
                // it is not going to spend any of itself emulating sound cards nothing will use
                .set("sblaster", "sbtype", "none")
                .set("gus", "gus", "false")
                .set("speaker", "pcspeaker", "false")
                .set("speaker", "tandy", "off")
                .set("speaker", "disney", "false")
                .set("joystick", "joysticktype", "none")
                .set("cpu", "cycles", "max")
                .set("cpu", "core", CORE)
                // dosbox's "max" holds the emulated cpu to about 90% of what the host will give
                // it. Turbo takes that brake off; it does not make the machine run away, because
                // the queue blocking is what paces it and that still holds.
                .turbo(true)
                // there is no screen to draw, so this is emulation nobody is going to look at
                .set("render", "frameskip", "10")
                .exitWhenProgramFinishes(true)
                .waveOutSink(new Sink())
                .stdioSink(new Log());
        dosbox.start();
    }

    /** what the emulated player writes to its waveOut device */
    private class Sink implements AudioSink {

        @Override
        public void open(int sampleRate, int sampleSizeInBits, int channels) {
logger.log(Level.DEBUG, "faith type4: waveOut " + sampleRate + "Hz " + sampleSizeInBits + "bit " + channels + "ch");
        }

        @Override
        public void write(byte[] data, int offset, int length) {
            producedFrames += length / FRAME_SIZE;

            if (!sounding) {
                int start = firstNonZero(data, offset, length);
                if (start < 0) {
                    // dropped, not queued: the silence in front of the first note would
                    // otherwise fill the cushion that is meant to carry the start of the song.
                    // jdosbox drops it at the device first, so little reaches here - but this is
                    // also what notices a player that never sounds at all
                    droppedBytes += length;
                    if (droppedBytes > (long) SAMPLE_RATE * FRAME_SIZE * SILENCE_LIMIT_SECONDS) {
logger.log(Level.WARNING, "faith type4: nothing but silence after " + SILENCE_LIMIT_SECONDS + "s, giving up");
                        gaveUp = true;
                        queue.finish();
                    }
                    return;
                }
                // align to a frame so the channels do not swap
                start -= (start - offset) % FRAME_SIZE;
                sounding = true;
logger.log(Level.DEBUG, "faith type4: sound starts, " + droppedBytes + " bytes of silence dropped");
                length -= start - offset;
                offset = start;
            }

            // blocking here is the point: it is the emulated sound card taking its samples at
            // the rate they are heard, which is what holds the synthesizer to the song's tempo
            queue.write(data, offset, length);
        }

        @Override
        public void close() {
logger.log(Level.DEBUG, "faith type4: waveOut closed");
        }
    }

    private static int firstNonZero(byte[] b, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (b[i] != 0) {
                return i;
            }
        }
        return -1;
    }

    /** takes the lines rts4c prints */
    private class Log implements StdioSink {

        private final StringBuilder pending = new StringBuilder();

        @Override
        public void write(byte[] data, int offset, int length, long frames) {
            pending.append(new String(data, offset, length, StandardCharsets.UTF_8));
            int at;
            while ((at = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, at).trim();
                pending.delete(0, at + 1);
                if (!line.isEmpty()) {
                    log.add(line);
logger.log(Level.DEBUG, line);
                }
            }
        }
    }

    /**
     * Takes the next samples the emulated player has made.
     *
     * @param timeoutMillis how long to wait for them before giving up and returning short
     * @return bytes read; 0 means either the song is over ({@link #isFinished}) or the emulator
     *         did not keep up
     */
    public int read(byte[] b, int offset, int length, long timeoutMillis) {
        if (queue == null) {
            return 0;
        }
        if (!primed) {
            // nothing is handed over until the cushion is built, or until it becomes clear that
            // there will never be that much - a song shorter than the cushion, or a player that
            // has finished or failed
            int wanted = (int) (Math.min(CUSHION_SECONDS, QUEUE_SECONDS * 0.9) * SAMPLE_RATE * FRAME_SIZE);
            if (!awaitCushion(wanted, deadline(timeoutMillis))
                    && !queue.isDrained() && isRunning()) {
                return 0;
            }
            primed = true;
logger.log(Level.DEBUG, "faith type4: primed with %.1fs".formatted(getCushionSeconds()));
        }
        long deadline = deadline(timeoutMillis);
        int n;
        // in slices, so that a machine which has finished is noticed then rather than at the end
        // of the caller's timeout: the last buffer of a song would otherwise be followed by the
        // whole of it spent waiting for audio that is never coming
        while ((n = queue.read(b, offset, length, slice(deadline))) == 0) {
            if (queue.isDrained() || !isRunning()) {
                queue.finish();
                break;
            }
            if (System.nanoTime() - deadline >= 0) {
                break;
            }
        }
        consumedFrames += n / FRAME_SIZE;
        return n;
    }

    /** is the emulated PC still going? */
    private boolean isRunning() {
        return dosbox != null && dosbox.isRunning();
    }

    private static long deadline(long timeoutMillis) {
        return System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    /** how long to wait for before looking up again, in milliseconds and never zero */
    private static long slice(long deadline) {
        long remain = (deadline - System.nanoTime()) / 1_000_000L;
        return Math.clamp(remain, 1, 100);
    }

    /** waits for the cushion, looking up often enough to notice a machine that has gone */
    private boolean awaitCushion(int wanted, long deadline) {
        while (queue.available() < wanted) {
            if (queue.isDrained() || !isRunning() || System.nanoTime() - deadline >= 0) {
                return queue.available() >= wanted;
            }
            queue.awaitAtLeast(wanted, slice(deadline));
        }
        return true;
    }

    /** is the song over - the machine gone and the queue dry, or never a sound out of it? */
    public boolean isFinished() {
        return dosbox == null || gaveUp
                || (!dosbox.isRunning() && (queue == null || queue.available() == 0));
    }

    /** Shuts the machine down and takes the song's scratch directory with it. */
    public void stop() {
logger.log(Level.DEBUG, "faith type4: " + getStatistics());
        if (queue != null) {
            queue.close();
        }
        if (dosbox != null) {
            dosbox.stop();
            dosbox = null;
        }
        if (work != null) {
            try (Stream<Path> walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
logger.log(Level.DEBUG, "faith type4: leaving " + p + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
logger.log(Level.DEBUG, "faith type4: leaving " + work + ": " + e.getMessage());
            }
            work = null;
        }
    }
}
