/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.faith;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFormat;

import jdos.api.AudioSink;
import jdos.api.JDosBox;
import jdos.api.StdioSink;

import static java.lang.System.getLogger;


/**
 * The Type 4 synthesizer as a thing you play rather than a thing you give a song to: midi goes
 * in as it happens, audio comes out while it does.
 * <p>
 * {@link FaithType4Player} is the other way round - it knows the whole song before it plays a
 * note of it, works out the sample every event falls on, and hands the emulated PC the lot. That
 * is the better way to hear a file, and it is no use at all to a {@code javax.sound.midi}
 * synthesizer, which is handed one message at a time by whoever is playing and never told what is
 * coming next. So this runs the same {@code rts4c.exe} in its {@code -live} mode, where there are
 * no timestamps and the arriving is the timing.
 * <p>
 * <b>How a message gets in.</b> The emulated PC has no pipe and no socket; what it has is a
 * mounted host directory. So the channel is a file: this appends a record to it and the program
 * inside, which has it open and keeps reading, picks it up at the next block. A block is 128
 * frames, so what that costs in timing is nothing - what costs is that the program only gets to
 * look between one waveOut buffer and the next, which is why the buffers in {@code -live} are
 * a handful of blocks where the song player's are sixteen.
 * <p>
 * <b>What the latency is.</b> The emulated sound card holds {@link #BUFFERS} buffers of
 * {@link #BLOCKS} blocks, the queue here holds {@link #QUEUE_FRAMES} frames, and whatever line
 * the caller plays through holds its own. {@link #getLatencyMicros} is the first two, which are
 * the parts this knows about. Smaller is closer to the key press and nearer to the emulator
 * running out of road; the machine synthesizes about twice as fast as it plays, so the margin is
 * that, minus whatever else the host is doing.
 * <p>
 * <b>One machine to a JVM.</b> jdosbox keeps its machine in statics, so a device that is open is
 * the only one, and no {@link FaithType4Player} can run beside it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 * @see FaithType4Player
 */
public class FaithType4Device {

    private static final Logger logger = getLogger(FaithType4Device.class.getName());

    /** what the dll plays at, after the resampler it puts its own 32000Hz through */
    public static final int SAMPLE_RATE = FaithType4Player.SAMPLE_RATE;

    public static final int CHANNELS = FaithType4Player.CHANNELS;

    /** stereo, sixteen bits */
    public static final int FRAME_SIZE = FaithType4Player.FRAME_SIZE;

    /** frames in one of the dll's blocks, which is the only size it renders */
    private static final int BLOCK_FRAMES = 128;

    /** which of the dll's voice sets: 0 and 1 have 48 voices, 2 has 64 */
    private static final int MODE = Integer.getInteger("vavi.sound.mfi.faith.mode", 0);

    /** how loud, times 256; see {@link FaithType4Renderer} for where three comes from */
    private static final int GAIN = (int) (Double.parseDouble(
            System.getProperty("vavi.sound.mfi.faith.gain", "3.0")) * 256);

    /**
     * Blocks to a waveOut buffer, which is how far a message can be from the block it is played
     * at: the program inside reads the stream between blocks, but it only gets to run between
     * buffers.
     */
    private static final int BLOCKS = Integer.getInteger("vavi.sound.mfi.faith.live.blocks", 4);

    /** waveOut buffers in flight, which is the emulator's room to be late in */
    private static final int BUFFERS = Integer.getInteger("vavi.sound.mfi.faith.live.buffers", 4);

    /**
     * How much audio may sit between the emulated sound card and the caller.
     * <p>
     * This is the whole of the elasticity: the card cannot get further ahead than this, because
     * {@link PcmQueue#write} stops it, and the caller cannot get further behind. Every frame of
     * it is a frame of delay between a key going down and the sound of it.
     */
    private static final int QUEUE_FRAMES =
            Integer.getInteger("vavi.sound.mfi.faith.live.queue", 2048);

    /** how much memory the emulated PC gets, in megabytes */
    private static final String MEMORY = System.getProperty("vavi.sound.mfi.faith.memory", "32");

    /** the recompiling core; see {@link FaithType4Player} for why it is not a choice */
    private static final String CORE = System.getProperty("vavi.sound.mfi.faith.core", "dynamic");

    /** the longest message {@code rts4c} will take, which is a UCS voice rather than a key press */
    private static final int MAX_MESSAGE = 4096;

    /** what the host says when there will be no more */
    private static final int STOP = 0xffff_ffff;

    /** how long to wait for the emulated PC to make its first sound before giving up on it [ms] */
    private static final long START_TIMEOUT = 20_000;

    private Path work;

    private JDosBox dosbox;

    /** read by whoever is taking the audio, replaced by whoever opens the machine */
    private volatile PcmQueue queue;

    /** the growing file the emulated PC is reading its midi out of */
    private FileChannel stream;

    /** what the program on the emulated PC wrote, one line to an entry */
    private final List<String> log = new CopyOnWriteArrayList<>();

    /** set once the emulated synthesizer has handed over its first buffer */
    private volatile boolean sounding;

    /** every frame the emulated synthesizer has produced */
    private volatile long producedFrames;

    /** frames handed to the caller, which is what has been heard bar the caller's own buffer */
    private volatile long consumedFrames;

    /** how much silence was handed over in place of audio the emulator did not have ready */
    private volatile long starvedFrames;

    private volatile boolean open;

    /** is there a Type 4 synthesizer to play? */
    public static boolean isAvailable() {
        return FaithType4Player.isAvailable();
    }

    /** the format everything that comes out of here is in */
    public static AudioFormat audioFormat() {
        return new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
    }

    /** 48 voices, or 64 in the third voice set */
    public static int getMaxPolyphony() {
        return MODE == 2 ? 64 : 48;
    }

    /**
     * How long after a message is sent the sound of it reaches {@link #read}, which is what the
     * emulated sound card is holding plus what the queue here is [us].
     */
    public static long getLatencyMicros() {
        return (long) (BLOCKS * BUFFERS * BLOCK_FRAMES + QUEUE_FRAMES) * 1_000_000 / SAMPLE_RATE;
    }

    public boolean isOpen() {
        return open;
    }

    /** what the program on the emulated PC had to say, in the order it said it */
    public List<String> getLog() {
        return List.copyOf(log);
    }

    /** the lines that say why nothing came out */
    public List<String> getProblems() {
        return log.stream().filter(l -> l.startsWith("rts4c: error")).toList();
    }

    /** frames handed over so far */
    public long getConsumedFrames() {
        return consumedFrames;
    }

    /** frames of silence handed over because the emulator did not have the audio ready in time */
    public long getStarvedFrames() {
        return starvedFrames;
    }

    /** what the emulated synthesizer has been up to, for a log line or a test */
    public String getStatistics() {
        return "%.1fs produced, %.1fs taken, %.3fs of silence for want of it".formatted(
                producedFrames / (double) SAMPLE_RATE,
                consumedFrames / (double) SAMPLE_RATE,
                starvedFrames / (double) SAMPLE_RATE);
    }

    /**
     * Boots the machine and waits for the synthesizer inside it to start playing - which, since
     * it plays silence until it is given something else, is as soon as it is ready.
     */
    public synchronized void open() throws IOException {
        if (open) {
            return;
        }
        if (!isAvailable()) {
            throw new IOException("no " + FaithType4Player.DLL + " under "
                    + FaithType4Player.toolsDirectory()
                    + "; set -D" + FaithType4Player.PATH_KEY + "=<dir>");
        }

        // the win32 layer only ever knows one drive, so the program, the dll and the stream the
        // midi arrives by have to sit on it together
        work = Files.createTempDirectory("vavi-faith-type4-live-");
        try {
            try (InputStream in = FaithType4Device.class.getResourceAsStream(FaithType4Player.RTS4C)) {
                if (in == null) {
                    throw new IOException("no " + FaithType4Player.RTS4C + " in the jar");
                }
                Files.write(work.resolve("rts4c.exe"), in.readAllBytes());
            }
            Files.copy(FaithType4Player.toolsDirectory().toPath().resolve(FaithType4Player.DLL),
                       work.resolve(FaithType4Player.DLL));

            // the header goes down before the machine boots, so the program finds it there
            stream = FileChannel.open(work.resolve("live.rt4"), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ByteBuffer header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RT4L".getBytes(StandardCharsets.US_ASCII))
                  .putInt(MODE).putInt(GAIN).putInt(BLOCKS).putInt(BUFFERS);
            write(header.flip());

            queue = new PcmQueue(QUEUE_FRAMES * FRAME_SIZE);

            // there is nothing to draw and nobody to look at it
            if (System.getProperty("jdos.novideo") == null) {
                System.setProperty("jdos.novideo", "true");
            }

            dosbox = new JDosBox()
                    .arg("-m", MEMORY)
                    .mount('c', work.toFile())
                    .command("c:")
                    // absolute paths: the win32 layer's idea of where it is need not be the drive's
                    .command("rts4c.exe -live c:\\live.rt4")
                    // nothing on the machine's own mixer is wanted, and not opening a line for it
                    // keeps it from fighting this for the host's audio device
                    .set("mixer", "nosound", "true")
                    .set("midi", "mididevice", "none")
                    // the emulated PC's only job is to synthesize, so it is not going to spend
                    // any of itself emulating sound cards nothing will use
                    .set("sblaster", "sbtype", "none")
                    .set("gus", "gus", "false")
                    .set("speaker", "pcspeaker", "false")
                    .set("speaker", "tandy", "off")
                    .set("speaker", "disney", "false")
                    .set("joystick", "joysticktype", "none")
                    .set("cpu", "cycles", "max")
                    .set("cpu", "core", CORE)
                    // the queue is what paces this, and it still does with the brake off
                    .turbo(true)
                    // there is no screen to draw, so this is emulation nobody is going to look at
                    .set("render", "frameskip", "10")
                    .exitWhenProgramFinishes(true)
                    .waveOutSink(new Sink(queue))
                    .stdioSink(new Log());
            try {
                dosbox.start();
            } catch (IllegalStateException e) {
                // jdosbox keeps its machine in statics, so there is only ever one - and what
                // has it is worth saying, because it is something else in this jar
                throw new IOException("another emulated PC is running in this JVM: "
                        + e.getMessage(), e);
            }
            open = true;

            if (!awaitSounding()) {
                String why = getProblems().isEmpty()
                        ? "it was still booting after " + START_TIMEOUT + "ms"
                        : String.join("; ", getProblems());
                throw new IOException("the Type 4 synthesizer would not start: " + why);
            }
logger.log(Level.DEBUG, "faith type4 live: playing, latency " + getLatencyMicros() / 1000 + "ms");
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** waits for the first buffer, which the program sends as soon as it has opened the device */
    private boolean awaitSounding() {
        long deadline = System.nanoTime() + START_TIMEOUT * 1_000_000L;
        while (!sounding && dosbox.isRunning() && System.nanoTime() - deadline < 0) {
            queue.awaitAtLeast(FRAME_SIZE, 50);
        }
        return sounding;
    }

    /**
     * Plays a midi message, now.
     * <p>
     * A channel message goes in as it stands and an exclusive goes down the dll's own door for
     * one; anything else - a meta message, which is a sequencer's business and not a
     * synthesizer's - has nowhere to go and is dropped.
     */
    public void send(byte[] message) {
        if (message.length == 0 || message.length > MAX_MESSAGE) {
            return;
        }
        int status = message[0] & 0xff;
        if (status < 0x80 || status == 0xff) {
            return;
        }
        // one record, one write: a reader that finds half of it waits for the rest, but there is
        // no reason to make it wait
        ByteBuffer record = ByteBuffer.allocate(4 + (message.length + 3 & ~3))
                                      .order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(message.length).put(message);
        // the whole record, padding and all - what a reader is waiting for is the padding too
        write(record.clear());
    }

    /** appends to the stream the emulated PC is reading, or gives up quietly once it has gone */
    private synchronized void write(ByteBuffer buffer) {
        if (stream == null) {
            return;
        }
        try {
            while (buffer.hasRemaining()) {
                stream.write(buffer);
            }
        } catch (IOException e) {
logger.log(Level.DEBUG, "faith type4 live: " + e.getMessage());
        }
    }

    /**
     * Takes the samples the emulated synthesizer has made, and silence for any it has not.
     * <p>
     * A synthesizer that is open is playing whether or not anyone is playing it, so this always
     * returns what was asked for: what the emulator did not have ready in time is silence, which
     * is a gap the listener hears rather than a click the line makes when it runs dry.
     *
     * @return bytes, which is {@code length} rounded down to a frame unless the device has shut
     */
    public int read(byte[] b, int offset, int length) {
        PcmQueue queue = this.queue;
        if (queue == null) {
            return 0;
        }
        length -= length % FRAME_SIZE;
        int read = 0;
        while (read < length) {
            int n = queue.read(b, offset + read, length - read, 50);
            if (n == 0) {
                if (queue.isDrained() || !isRunning()) {
                    break;
                }
                // the emulator is late; hand over the silence it would have handed over
                Arrays.fill(b, offset + read, offset + length, (byte) 0);
                starvedFrames += (length - read) / FRAME_SIZE;
                read = length;
                break;
            }
            read += n;
        }
        consumedFrames += read / FRAME_SIZE;
        return read;
    }

    /** is the emulated PC still going? */
    private boolean isRunning() {
        return dosbox != null && dosbox.isRunning();
    }

    /** Says there will be no more, shuts the machine down and takes its scratch directory. */
    public synchronized void close() {
        if (dosbox != null && dosbox.isRunning() && stream != null) {
            // the program's own way out: it finishes the buffer it is on, hands back what the
            // sound card is still holding and lets the win32 layer clean up after it
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(STOP).flip());
            dosbox.await(2_000);
        }
logger.log(Level.DEBUG, "faith type4 live: " + getStatistics());
        open = false;
        if (queue != null) {
            // closed, not cleared: the emulator's thread is still writing to it, and a closed
            // queue is where those samples are meant to go
            queue.close();
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
logger.log(Level.DEBUG, "faith type4 live: " + e.getMessage());
            }
            stream = null;
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
logger.log(Level.DEBUG, "faith type4 live: leaving " + p + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
logger.log(Level.DEBUG, "faith type4 live: leaving " + work + ": " + e.getMessage());
            }
            work = null;
        }
    }

    /** what the emulated synthesizer writes to its waveOut device */
    private class Sink implements AudioSink {

        /**
         * The queue this run's samples go to.
         * <p>
         * Its own reference rather than the field: this is called from the emulator's thread,
         * which is still handing over buffers after {@link FaithType4Device#close} has stopped
         * waiting for it, and a queue that has been closed takes them and drops them where a
         * field that has been cleared would not.
         */
        private final PcmQueue queue;

        Sink(PcmQueue queue) {
            this.queue = queue;
        }

        @Override
        public void open(int sampleRate, int sampleSizeInBits, int channels) {
logger.log(Level.DEBUG, "faith type4 live: waveOut " + sampleRate + "Hz " + sampleSizeInBits + "bit " + channels + "ch");
        }

        @Override
        public void write(byte[] data, int offset, int length) {
            producedFrames += length / FRAME_SIZE;
            sounding = true;
            // blocking here is the clock: the emulated sound card takes its samples at the rate
            // they are heard at, and cannot get more than a queue ahead of the listener
            queue.write(data, offset, length);
        }

        @Override
        public void close() {
logger.log(Level.DEBUG, "faith type4 live: waveOut closed");
            queue.finish();
        }
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
}
