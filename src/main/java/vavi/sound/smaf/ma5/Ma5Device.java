/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma5;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
import vavi.sound.mfi.faith.PcmQueue;

import static java.lang.System.getLogger;


/**
 * Yamaha's MA-5 emulator, {@code M5_EmuSmw5.dll} - what mmftool plays a SMAF file on - as a thing
 * you play rather than a thing you give a song to: midi goes in as it happens, audio comes out
 * while it does.
 * <p>
 * The dll is a 32 bit x86 Windows one, so it runs on an emulated PC (jdosbox), under
 * {@code m5live.exe}, a front end of a few lines which is in this jar, source and all. The dll
 * has a door for midi as it happens ({@code SetMidiMsg}) beside its file player, which is what
 * mmftool's own piano roll plays through, and that door is all this uses: see {@code m5live.c}.
 * <p>
 * <b>How a message gets in.</b> The emulated PC has no pipe and no socket; what it has is a
 * mounted host directory. So the channel is a file: this appends a record to it and the program
 * inside, which has it open and keeps reading, hands it to the dll, as
 * {@link vavi.sound.mfi.faith.FaithType4Device} does it.
 * <p>
 * <b>How the audio gets out.</b> Unlike the faith dll, which is handed a buffer and fills it, this
 * one opens a waveOut device of its own when it is initialized and keeps it fed from a thread of
 * its own. That device is what the host sees ({@link AudioSink}), and blocking it when the queue
 * here is full ({@link PcmQueue#write}) is what holds the emulated PC to the rate the audio is
 * heard at. Its buffers are the dll's to size, so the latency is what the queue here holds plus
 * whatever the dll keeps in flight, which is measured rather than known: see
 * {@link #getLatencyMicros}.
 * <p>
 * The emulated sound card drops the silence a program writes before it first sounds, and does not
 * pace it while it does: until the first note the dll runs as fast as the emulator can go, and the
 * cushion builds up from the first note on.
 * <p>
 * <b>One machine to a JVM.</b> jdosbox keeps its machine in statics, so a device that is open is
 * the only one, and nothing else booting a machine may run beside it.
 *
 * <h4>system properties</h4>
 * <ul>
 * <li>{@code vavi.sound.smaf.ma5.path} ... the directory where {@code M5_EmuSmw5.dll} and
 *     {@code M5_EmuHw.dll} are (mmftool's), default {@code /usr/local/src/mmftool}</li>
 * <li>{@code vavi.sound.smaf.ma5.rate} ... what the dll synthesizes at, 22050, 32000, 44100 or
 *     48000, default 32000: synthesis costs in proportion to it, and a heavy MA-5 song is only
 *     just synthesized in real time at 48000</li>
 * <li>{@code vavi.sound.smaf.ma5.queue} ... frames the host holds between the emulated sound card
 *     and the listener, default 4096</li>
 * </ul>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano initial version <br>
 */
public class Ma5Device {

    private static final Logger logger = getLogger(Ma5Device.class.getName());

    /** the dll itself */
    public static final String DLL = "M5_EmuSmw5.dll";

    /** where the dll is */
    public static final String PATH_KEY = "vavi.sound.smaf.ma5.path";

    /** the front end, in this jar */
    static final String M5LIVE = "/vavi/sound/smaf/ma5/m5live.exe";

    /** what the dll synthesizes at */
    private static final int RATE = Integer.getInteger("vavi.sound.smaf.ma5.rate", 32000);

    /**
     * How much audio may sit between the emulated sound card and the caller.
     * <p>
     * The card cannot get further ahead than this, because {@link PcmQueue#write} stops it, and
     * every frame of it is a frame of delay between a message and the sound of it.
     */
    private static final int QUEUE_FRAMES = Integer.getInteger("vavi.sound.smaf.ma5.queue", 4096);

    /** how much memory the emulated PC gets, in megabytes */
    private static final String MEMORY = System.getProperty("vavi.sound.smaf.ma5.memory", "32");

    /** the longest message {@code m5live} takes, the wave of a song rather than a key press */
    private static final int MAX_MESSAGE = 32768;

    /** what the host says when there will be no more */
    private static final int STOP = 0xffff_ffff;

    /** how long to wait for the dll to come up [ms] */
    private static final long START_TIMEOUT = 30_000;

    private Path work;

    private JDosBox dosbox;

    /** read by whoever is taking the audio, replaced by whoever opens the machine */
    private volatile PcmQueue queue;

    /** the growing file the emulated PC is reading its midi out of */
    private FileChannel stream;

    /** what the program on the emulated PC wrote, one line to an entry */
    private final List<String> log = new CopyOnWriteArrayList<>();

    /** set once {@code m5live} has said the dll is up */
    private volatile boolean ready;

    /** what the dll's waveOut device was opened as */
    private volatile AudioFormat format;

    /** the biggest buffer the dll handed its device, which is what it keeps in flight */
    private volatile int bufferFrames;

    /** every frame the emulated synthesizer has produced */
    private volatile long producedFrames;

    /** frames handed to the caller */
    private volatile long consumedFrames;

    /** the loudest sample the emulated synthesizer has produced, 0 ~ 32768 */
    private volatile int peak;

    /** samples at ±32767, where the dll clips (it does so symmetrically) */
    private volatile long clipped;

    /** how much silence was handed over in place of audio the emulator did not have ready */
    private volatile long starvedFrames;

    private volatile boolean open;

    /** the directory where the dll is, as the system property says */
    public static File toolDirectory() {
        return new File(System.getProperty(PATH_KEY, "/usr/local/src/mmftool"));
    }

    /** is there an MA-5 to play? */
    public static boolean isAvailable() {
        return new File(toolDirectory(), DLL).exists();
    }

    /** what the emulated PC needs beside the front end: the dlls and the voice table */
    private static boolean isToolFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path) && (name.endsWith(".dll") || name.endsWith(".inf") || name.endsWith(".vm3"));
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * The format everything that comes out of here is in, which is what the dll opened its device
     * as: known once {@link #open} has returned, the rate asked for before that.
     */
    public AudioFormat getFormat() {
        AudioFormat format = this.format;
        return format != null ? format : new AudioFormat(RATE, 16, 2, true, false);
    }

    private int frameSize() {
        return getFormat().getFrameSize();
    }

    /**
     * How long after a message is sent the sound of it reaches {@link #read} [us]: the queue here,
     * and the buffers the dll has been seen to hand its device.
     */
    public long getLatencyMicros() {
        return (long) (QUEUE_FRAMES + bufferFrames) * 1_000_000 / (int) getFormat().getSampleRate();
    }

    /** what the program on the emulated PC had to say, in the order it said it */
    public List<String> getLog() {
        return List.copyOf(log);
    }

    /** the lines that say why nothing came out */
    public List<String> getProblems() {
        return log.stream().filter(l -> l.startsWith("m5live: error") || l.startsWith("m5live: not taken")).toList();
    }

    /** frames the emulated synthesizer has produced, bar the silence it opened with */
    public long getProducedFrames() {
        return producedFrames;
    }

    /** the loudest sample so far, 0 ~ 32768: whether anything has been heard at all */
    public int getPeak() {
        return peak;
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
        float rate = getFormat().getSampleRate();
        return "%.1fs produced, %.1fs taken, %.3fs of silence for want of it, %d samples clipped".formatted(
                producedFrames / rate, consumedFrames / rate, starvedFrames / rate, clipped);
    }

    /** Boots the machine and waits for the dll inside it to be up. */
    public synchronized void open() throws IOException {
        if (open) {
            return;
        }
        if (!isAvailable()) {
            throw new IOException("no " + DLL + " under " + toolDirectory() + "; set -D" + PATH_KEY + "=<dir>");
        }

        // the win32 layer only ever knows one drive, so the program, the dlls and the stream the
        // midi arrives by have to sit on it together
        work = Files.createTempDirectory("vavi-smaf-ma5-live-");
        try {
            try (InputStream in = Ma5Device.class.getResourceAsStream(M5LIVE)) {
                if (in == null) {
                    throw new IOException("no " + M5LIVE + " in the jar");
                }
                Files.write(work.resolve("m5live.exe"), in.readAllBytes());
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(toolDirectory().toPath(), Ma5Device::isToolFile)) {
                for (Path f : files) {
                    Files.copy(f, work.resolve(f.getFileName().toString()));
                }
            }

            // the header goes down before the machine boots, so the program finds it there
            stream = FileChannel.open(work.resolve("live.m5l"), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[] {'M', '5', 'L', 0}).putInt(RATE);
            write(header.flip());

            // sized for the widest the dll may open its device as, 16 bit stereo
            queue = new PcmQueue(QUEUE_FRAMES * 4);

            // there is nothing to draw and nobody to look at it
            if (System.getProperty("jdos.novideo") == null) {
                System.setProperty("jdos.novideo", "true");
            }

            dosbox = new JDosBox()
                    .arg("-m", MEMORY)
                    .mount('c', work.toFile())
                    .command("c:")
                    // absolute paths: the win32 layer's idea of where it is need not be the drive's
                    .command("m5live.exe c:\\live.m5l")
                    // nothing on the machine's own mixer is wanted, and not opening a line for it
                    // keeps it from fighting this for the host's audio device
                    .set("mixer", "nosound", "true")
                    .set("midi", "mididevice", "none")
                    // the emulated PC's only job is to synthesize
                    .set("sblaster", "sbtype", "none")
                    .set("gus", "gus", "false")
                    .set("speaker", "pcspeaker", "false")
                    .set("speaker", "tandy", "off")
                    .set("speaker", "disney", "false")
                    .set("joystick", "joysticktype", "none")
                    .set("cpu", "cycles", "max")
                    // the queue is what paces this, and it still does with the brake off
                    .turbo(true)
                    .set("render", "frameskip", "10")
                    .exitWhenProgramFinishes(true)
                    .waveOutSink(new Sink(queue))
                    .stdioSink(new Log());
            try {
                dosbox.start();
            } catch (IllegalStateException e) {
                // jdosbox keeps its machine in statics, so there is only ever one
                throw new IOException("another emulated PC is running in this JVM: " + e.getMessage(), e);
            }
            open = true;

            if (!awaitReady()) {
                String why = getProblems().isEmpty()
                        ? "it was still booting after " + START_TIMEOUT + "ms"
                        : String.join("; ", getProblems());
                throw new IOException("the MA-5 would not start: " + why);
            }
logger.log(Level.DEBUG, "smaf ma5 live: ready, " + getFormat());
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** waits for {@code m5live} to say the dll is up, and for the dll to have opened its device */
    private boolean awaitReady() {
        long deadline = System.nanoTime() + START_TIMEOUT * 1_000_000L;
        while ((!ready || format == null) && dosbox.isRunning() && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ready && format != null;
    }

    /**
     * Plays a midi message, now: a channel message as it stands, an exclusive whole
     * ({@code f0 ... f7}). A meta message has nowhere to go and is dropped.
     */
    public void send(byte[] message) {
        if (message.length == 0 || message.length > MAX_MESSAGE) {
            return;
        }
        int status = message[0] & 0xff;
        if (status < 0x80 || status == 0xff) {
            return;
        }
        ByteBuffer record = ByteBuffer.allocate(4 + (message.length + 3 & ~3)).order(ByteOrder.LITTLE_ENDIAN);
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
logger.log(Level.DEBUG, "smaf ma5 live: " + e.getMessage());
        }
    }

    /**
     * Takes the samples the emulated synthesizer has made, and silence for any it has not, as
     * {@link vavi.sound.mfi.faith.FaithType4Device#read} does.
     *
     * @return bytes, which is {@code length} rounded down to a frame unless the device has shut
     */
    public int read(byte[] b, int offset, int length) {
        PcmQueue queue = this.queue;
        if (queue == null) {
            return 0;
        }
        int frameSize = frameSize();
        length -= length % frameSize;
        int read = 0;
        while (read < length) {
            int n = queue.read(b, offset + read, length - read, 50);
            if (n == 0) {
                if (queue.isDrained() || !isRunning()) {
                    break;
                }
                // the emulator is late (or has not sounded yet); hand over the silence it would have
                Arrays.fill(b, offset + read, offset + length, (byte) 0);
                starvedFrames += (length - read) / frameSize;
                read = length;
                break;
            }
            read += n;
        }
        consumedFrames += read / frameSize;
        return read;
    }

    /** is the emulated PC still going? */
    private boolean isRunning() {
        return dosbox != null && dosbox.isRunning();
    }

    /** Says there will be no more, shuts the machine down and takes its scratch directory. */
    public synchronized void close() {
        if (dosbox != null && dosbox.isRunning() && stream != null) {
            // the program's own way out, which lets the dll close its device
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(STOP).flip());
            dosbox.await(2_000);
        }
logger.log(Level.DEBUG, "smaf ma5 live: " + getStatistics());
        open = false;
        ready = false;
        if (queue != null) {
            // closed, not cleared: the emulator's thread may still be writing to it
            queue.close();
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
logger.log(Level.DEBUG, "smaf ma5 live: " + e.getMessage());
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
logger.log(Level.DEBUG, "smaf ma5 live: leaving " + p + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
logger.log(Level.DEBUG, "smaf ma5 live: leaving " + work + ": " + e.getMessage());
            }
            work = null;
        }
    }

    /** what the dll writes to its waveOut device */
    private class Sink implements AudioSink {

        /** this run's queue, which a late write from the emulator's thread may still find closed */
        private final PcmQueue queue;

        Sink(PcmQueue queue) {
            this.queue = queue;
        }

        @Override
        public void open(int sampleRate, int sampleSizeInBits, int channels) {
            if (sampleSizeInBits != 16) {
logger.log(Level.WARNING, "smaf ma5 live: the dll opened its device " + sampleSizeInBits + " bit, taken as 16");
            }
            format = new AudioFormat(sampleRate, 16, channels, true, false);
logger.log(Level.DEBUG, "smaf ma5 live: waveOut " + sampleRate + "Hz " + sampleSizeInBits + "bit " + channels + "ch");
        }

        @Override
        public void write(byte[] data, int offset, int length) {
            int frames = length / frameSize();
            producedFrames += frames;
            if (frames > bufferFrames) {
                bufferFrames = frames;
            }
            int max = peak;
            long clips = 0;
            for (int i = offset; i + 1 < offset + length; i += 2) {
                int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                if (Math.abs(sample) >= Short.MAX_VALUE) clips++;
                max = Math.max(max, Math.abs(sample));
            }
            peak = max;
            clipped += clips;
            // blocking here is the clock: the emulated sound card takes its samples at the rate
            // they are heard at, and cannot get more than a queue ahead of the listener
            queue.write(data, offset, length);
        }

        @Override
        public void close() {
logger.log(Level.DEBUG, "smaf ma5 live: waveOut closed");
            queue.finish();
        }
    }

    /** takes the lines m5live prints */
    private class Log implements StdioSink {

        private final StringBuilder pending = new StringBuilder();

        @Override
        public void write(byte[] data, int offset, int length, long frames) {
            pending.append(new String(data, offset, length, StandardCharsets.US_ASCII));
            int at;
            while ((at = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, at).trim();
                pending.delete(0, at + 1);
                if (!line.isEmpty()) {
                    log.add(line);
                    if (line.startsWith("m5live: ready")) {
                        ready = true;
                    }
logger.log(Level.DEBUG, line);
                }
            }
        }
    }
}
