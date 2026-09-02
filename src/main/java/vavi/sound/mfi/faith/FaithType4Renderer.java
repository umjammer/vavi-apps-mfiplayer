/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.faith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mfi.MfiSystem;

import static java.lang.System.getLogger;


/**
 * The sequencer {@code rt_synth_4.dll} has not got, and the two ways of listening to what it
 * plays.
 * <p>
 * The dll is a synthesizer and nothing else: no file format, no sequencer, no clock. So the
 * timing is worked out here, in java, where the tempo map is, and what crosses to the emulated PC
 * is every message with the sample it falls on - see {@link #writeEvents}. The playing is
 * {@link FaithType4Player}'s.
 * <p>
 * {@link #play} is the one to use: the song starts a second or two in and keeps time against the
 * emulated sound card. {@link #render} is for when a file is wanted rather than a sound, and it
 * takes as long as the whole song takes to synthesize, which is about half its length.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
public class FaithType4Renderer {

    private static final System.Logger logger = getLogger(FaithType4Renderer.class.getName());

    /** where the authoring tool's {@code Tools} directory is */
    public static final String PATH_KEY = FaithType4Player.PATH_KEY;

    /** what the dll plays at, after the resampler it puts its own 32000Hz through */
    public static final int SAMPLE_RATE = FaithType4Player.SAMPLE_RATE;

    /** stereo, sixteen bits */
    public static final int FRAME_SIZE = FaithType4Player.FRAME_SIZE;

    /** the tempo a sequence has until it says otherwise, in microseconds a quarter note */
    private static final int DEFAULT_TEMPO = 500_000;

    /**
     * How loud, times 256.
     * <p>
     * The dll mixes for a phone's speaker: a song playing everything it has peaks around a
     * quarter of full scale, so this is the difference between that and something anyone would
     * want to listen to. It is a plain multiply with a clip at the end, and three is where the
     * authoring tool's own samples land a little under full scale with room left for a denser
     * song than any of them.
     */
    private static final int GAIN = (int) (Double.parseDouble(
            System.getProperty("vavi.sound.mfi.faith.gain", "3.0")) * 256);

    /** which of the dll's voice sets: 0 and 1 have 48 voices, 2 has 64 */
    private static final int MODE = Integer.getInteger("vavi.sound.mfi.faith.mode", 0);

    private FaithType4Renderer() {
    }

    /** the authoring tool's {@code Tools} directory, which is where the dll lives */
    public static File toolsDirectory() {
        return FaithType4Player.toolsDirectory();
    }

    /** is there a Type 4 synthesizer to play with? */
    public static boolean isAvailable() {
        return FaithType4Player.isAvailable();
    }

    /** the format everything here is in */
    public static AudioFormat audioFormat() {
        return new AudioFormat(SAMPLE_RATE, 16, FaithType4Player.CHANNELS, true, false);
    }

    /**
     * Plays a sequence, and returns when it is over.
     *
     * @param millis how much of it to play, or zero for all of it
     */
    public static void play(Sequence sequence, long millis) throws Exception {
        FaithType4Player player = new FaithType4Player(sequence, millis);
        player.start();
        try {
            AudioFormat format = audioFormat();
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                byte[] buffer = new byte[8192];
                while (!player.isFinished()) {
                    int read = player.read(buffer, 0, buffer.length, 1_000);
                    if (read > 0) {
                        line.write(buffer, 0, read);
                    }
                }
                line.drain();
            }
logger.log(System.Logger.Level.DEBUG, "faith type4: " + player.getStatistics());
        } finally {
            player.stop();
        }
    }

    /**
     * Plays an MFi file, and its adpcm track alongside.
     * <p>
     * The dll plays the notes and nothing else - MFi's own adpcm track is not something it has -
     * so the MFi sequencer runs beside it with everything but its exclusives thrown away, which
     * is what carries the adpcm and nothing that would be heard twice.
     */
    public static void play(Path mld, long millis) throws Exception {
        try (Adpcm ignored = new Adpcm(mld)) {
            play(sequenceOf(mld), millis);
        }
    }

    /**
     * The whole of a sequence, as a 44100Hz 16 bit stereo RIFF/WAVE.
     * <p>
     * This takes as long as the emulated PC needs to synthesize the song, which is about half
     * its length - so a three minute song is a minute and a half of waiting with nothing to hear.
     * That is what {@link #play} exists not to do.
     *
     * @param millis how much of it to render, or zero for all of it
     */
    public static byte[] render(Sequence sequence, long millis) throws IOException {
        FaithType4Player player = new FaithType4Player(sequence, millis);
        player.start();
        try {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(1 << 20);
            byte[] buffer = new byte[64 * 1024];
            while (!player.isFinished()) {
                int read = player.read(buffer, 0, buffer.length, 10_000);
                if (read > 0) {
                    pcm.write(buffer, 0, read);
                }
            }
            if (pcm.size() == 0) {
                throw new IOException(whatWentWrong(player));
            }
            return wave(pcm.toByteArray());
        } finally {
            player.stop();
        }
    }

    /** an MFi file, the same way */
    public static byte[] render(Path mld, long millis) throws IOException {
        return render(sequenceOf(mld), millis);
    }

    /** What to say when nothing came out. */
    private static String whatWentWrong(FaithType4Player player) {
        List<String> problems = player.getProblems();
        if (!problems.isEmpty()) {
            return String.join("; ", problems);
        }
        return "the synthesizer produced nothing and said nothing";
    }

    /**
     * The MFi as a midi sequence.
     * <p>
     * Read by {@link MfiSystem}, so a dialect vavi-sound does not read yet cannot be played here
     * either, however well the dll would have played it.
     */
    static Sequence sequenceOf(Path mld) throws IOException {
        if (!Files.isRegularFile(mld)) {
            throw new IOException("no such MFi file: " + mld);
        }
        try {
            return MfiSystem.toMidiSequence(MfiSystem.getSequence(mld.toFile()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** a RIFF/WAVE around raw 44100Hz 16 bit stereo samples */
    static byte[] wave(byte[] pcm) {
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
           .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
           .putShort((short) 1).putShort((short) FaithType4Player.CHANNELS)
           .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * FRAME_SIZE)
           .putShort((short) FRAME_SIZE).putShort((short) 16)
           .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length)
           .put(pcm);
        return out.array();
    }

    /**
     * Writes the song for the emulated PC to play: every message with the sample it falls on.
     * <p>
     * The format is the one at the top of {@code rts4c.c}. Only what the dll can do is written -
     * its channel messages, and the exclusives it has its own door for; a meta message is read
     * for its tempo and then left behind, since there is nothing on the far side that keeps time.
     *
     * @param output where to write it
     * @param millis how much of the sequence to take, or zero for all of it
     * @return how many frames that is, which is how long the song will be
     */
    static int writeEvents(Sequence sequence, Path output, long millis) throws IOException {
        List<MidiEvent> events = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                events.add(track.get(i));
            }
        }
        // a note released and another taken at the same instant should not be two notes at once:
        // the dll has a fixed voice pool, and a note on that comes first can steal a voice that
        // was about to come free on its own
        events.sort(Comparator.comparingLong(MidiEvent::getTick).thenComparingInt(event -> {
            if (!(event.getMessage() instanceof ShortMessage message)) {
                return 0;
            }
            int command = message.getCommand();
            return command == ShortMessage.NOTE_OFF
                    || (command == ShortMessage.NOTE_ON && message.getData2() == 0) ? 0 : 1;
        }));

        TempoMap tempo = new TempoMap(sequence.getResolution(), events);
        long limit = millis > 0 ? millis * SAMPLE_RATE / 1000 : Long.MAX_VALUE;

        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        long last = 0;
        for (MidiEvent event : events) {
            byte[] message = playable(event.getMessage());
            if (message == null) {
                continue;
            }
            long frame = tempo.frameAt(event.getTick());
            if (frame > limit) {
                continue;
            }
            int padded = message.length + 3 & ~3;
            if (buffer.remaining() < 8 + padded) {
                buffer = grow(buffer, 8 + padded);
            }
            buffer.putInt((int) frame).putInt(message.length).put(message)
                  .put(new byte[padded - message.length]);
            count++;
            last = Math.max(last, frame);
        }
        if (count == 0) {
            return 0;
        }

        // enough after the last message for what it started to die away on its own
        long frames = Math.min(last + (long) SAMPLE_RATE * 3 / 2, limit);
        ByteBuffer header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RT4\0".getBytes(StandardCharsets.US_ASCII))
              .putInt(MODE).putInt((int) frames).putInt(GAIN).putInt(count);
        try (var channel = Files.newByteChannel(output, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(header.flip());
            channel.write(buffer.flip());
        }
        return (int) frames;
    }

    /** the bytes of a message the dll has somewhere to put, or null for one it has not */
    private static byte[] playable(MidiMessage message) {
        if (message instanceof ShortMessage shortMessage) {
            int command = shortMessage.getCommand();
            if (command < ShortMessage.NOTE_OFF || command > ShortMessage.PITCH_BEND) {
                return null;
            }
            return shortMessage.getMessage();
        }
        if (message instanceof SysexMessage sysex && sysex.getStatus() == SysexMessage.SYSTEM_EXCLUSIVE) {
            return sysex.getMessage();
        }
        return null;
    }

    private static ByteBuffer grow(ByteBuffer buffer, int wanted) {
        ByteBuffer bigger = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, buffer.position() + wanted))
                                      .order(ByteOrder.LITTLE_ENDIAN);
        bigger.put(buffer.flip());
        return bigger;
    }

    /**
     * The MFi sequencer, running for its adpcm alone.
     * <p>
     * Everything but an exclusive is dropped on the way to the synthesizer, so the notes are
     * heard once - out of what the dll is playing - and the adpcm, which only ever arrives as an
     * exclusive, is heard at all.
     */
    private static class Adpcm implements AutoCloseable {

        /**
         * Where the first adpcm event sits relative to what the dll is playing, in whatever units
         * {@code vavi.sound.mobile.AudioEngine} counts them in.
         */
        private static final String LATENCY = System.getProperty("vavi.sound.mfi.faith.latency", "-35");

        private final vavi.sound.mfi.Sequencer sequencer = MfiSystem.getSequencer();
        private final vavi.sound.mfi.Synthesizer synthesizer = MfiSystem.getSynthesizer();
        private final String previous = System.getProperty("vavi.sound.mobile.AudioEngine.latency");

        Adpcm(Path mld) throws Exception {
            System.setProperty("vavi.sound.mobile.AudioEngine.latency", LATENCY);
            try {
                sequencer.open();
                synthesizer.open();
                Receiver receiver = synthesizer.getReceiver();
                sequencer.getTransmitter().setReceiver(new Receiver() {
                    @Override public void send(MidiMessage message, long timeStamp) {
                        if (message instanceof SysexMessage) {
                            receiver.send(message, timeStamp);
                        }
                    }
                    @Override public void close() {
                        receiver.close();
                    }
                });
                sequencer.setSequence(MfiSystem.getSequence(mld.toFile()));
                sequencer.start();
            } catch (Exception | Error e) {
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            for (Runnable step : new Runnable[] {sequencer::stop, sequencer::close, synthesizer::close}) {
                try {
                    step.run();
                } catch (Exception e) {
                    // one that never got as far as being opened has nothing to close
logger.log(System.Logger.Level.DEBUG, "faith type4: " + e.getMessage());
                }
            }
            if (previous == null) {
                System.clearProperty("vavi.sound.mobile.AudioEngine.latency");
            } else {
                System.setProperty("vavi.sound.mobile.AudioEngine.latency", previous);
            }
        }
    }

    /**
     * Ticks to samples.
     * <p>
     * Done here rather than by a real time sequencer because the whole song's timing has to be
     * known before the first block of it is synthesized.
     */
    static final class TempoMap {

        private final int resolution;

        /** where the tempo changed, and to what */
        private final List<long[]> changes = new ArrayList<>();

        TempoMap(int resolution, List<MidiEvent> events) {
            this.resolution = resolution;
            for (MidiEvent event : events) {
                if (event.getMessage() instanceof MetaMessage meta
                        && meta.getType() == 0x51 && meta.getData().length == 3) {
                    byte[] data = meta.getData();
                    changes.add(new long[] {event.getTick(),
                            (data[0] & 0xff) << 16 | (data[1] & 0xff) << 8 | data[2] & 0xff});
                }
            }
        }

        /** the sample a tick falls on */
        long frameAt(long tick) {
            long previous = 0;
            long micros = 0;
            long tempo = DEFAULT_TEMPO;
            for (long[] change : changes) {
                if (change[0] > tick) {
                    break;
                }
                micros += (change[0] - previous) * tempo / resolution;
                previous = change[0];
                tempo = change[1];
            }
            micros += (tick - previous) * tempo / resolution;
            return Math.round(micros * (double) SAMPLE_RATE / 1_000_000);
        }
    }
}
