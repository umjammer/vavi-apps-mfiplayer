/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.faith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * FaithType4RendererTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class FaithType4RendererTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    /** so junit can ask, which it cannot of a method on another class */
    static boolean isAvailable() {
        return FaithType4Renderer.isAvailable();
    }

    @Property(name = "vavi.test.volume")
    double volume = 0.2f;

    @Property
    String mld = "src/test/resources/test.mld";

    /** how much of a song a test renders; enough to hear it is a song [ms] */
    static final long TIME = 5_000;

    /** how long a song may take to start, which is a boot and a cushion and no more [ms] */
    static final long LATENCY_LIMIT = 15_000;

    @Test
    @DisplayName("ticks become samples, and only what the dll can play crosses over")
    void testWriteEvents() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 100);
        Track track = sequence.createTrack();
        track.add(event(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 2, 40, 0), 0));
        track.add(event(new ShortMessage(ShortMessage.NOTE_ON, 2, 60, 100), 100));
        MetaMessage tempo = new MetaMessage();
        tempo.setMessage(0x51, new byte[] {0x03, (byte) 0xd0, (byte) 0x90}, 3); // 250ms a quarter
        track.add(new MidiEvent(tempo, 100));
        track.add(event(new ShortMessage(ShortMessage.NOTE_OFF, 2, 60, 0), 200));

        Path file = Files.createTempFile("faith-type4-", ".rt4");
        try {
            // 200 ticks is a quarter at 500ms and one at 250ms, and a second and a half after it
            int frames = FaithType4Renderer.writeEvents(sequence, file, 0);
            assertEquals(33_075 + 44_100 * 3 / 2, frames);

            List<int[]> events = read(file, frames);
            assertEquals(List.of("0 c2 28", "22050 92 3c 64", "33075 82 3c 00"),
                         events.stream().map(FaithType4RendererTest::show).toList());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("a note released and one taken at the same tick go in that order")
    void testWriteEventsOrder() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 100);
        Track track = sequence.createTrack();
        track.add(event(new ShortMessage(ShortMessage.NOTE_ON, 0, 62, 100), 100));
        track.add(event(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 100));

        Path file = Files.createTempFile("faith-type4-", ".rt4");
        try {
            int frames = FaithType4Renderer.writeEvents(sequence, file, 0);
            assertEquals(List.of("22050 80 3c 00", "22050 90 3e 64"),
                         read(file, frames).stream().map(FaithType4RendererTest::show).toList());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("a song rendered on the emulated PC by the dll itself")
    @EnabledIf("isAvailable")
    void testRender() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
Debug.println(mld + ", " + Files.exists(Paths.get(mld)));

        byte[] wave = FaithType4Renderer.render(sequence(Paths.get(mld)), TIME);

        assertEquals("RIFF", new String(wave, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wave, 8, 4, StandardCharsets.US_ASCII));
        ByteBuffer header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, header.getShort(22), "channels");
        assertEquals(FaithType4Renderer.SAMPLE_RATE, header.getInt(24), "sample rate");
        assertEquals(16, header.getShort(34), "bits");
        assertEquals(wave.length - 44, header.getInt(40), "the data chunk is the whole of the rest");

        // no more than was asked for. It can be less: the silence a song starts with is dropped
        // rather than played, so what comes back begins at the first note
        long frames = (wave.length - 44) / FaithType4Renderer.FRAME_SIZE;
        long asked = TIME * FaithType4Renderer.SAMPLE_RATE / 1000;
        assertTrue(frames <= asked + 128, "frames: " + frames);
        assertTrue(frames > asked / 2, "frames: " + frames);

        // and it is a song, not silence
        int peak = 0;
        for (int i = 44; i + 1 < wave.length; i += 2) {
            peak = Math.max(peak, Math.abs(header.getShort(i)));
        }
Debug.println("peak: " + peak);
        assertTrue(peak > 1000, "peak: " + peak);
    }

    /**
     * The one that matters: a player that renders the song before it plays any of it takes a
     * minute to start on a three minute song, which is not playing it.
     */
    @Test
    @DisplayName("the song starts before it has been synthesized")
    @EnabledIf("isAvailable")
    void testStartsPromptly() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        FaithType4Player player = new FaithType4Player(sequence(Paths.get(mld)), 0);
        long start = System.currentTimeMillis();
        player.start();
        try {
            byte[] buffer = new byte[8192];
            int read = 0;
            while (read == 0 && !player.isFinished()) {
                read = player.read(buffer, 0, buffer.length, LATENCY_LIMIT);
            }
            long took = System.currentTimeMillis() - start;
Debug.println("first sound after " + took + "ms, " + player.getStatistics());
            assertTrue(read > 0, "nothing came out: " + player.getLog());
            assertTrue(took < LATENCY_LIMIT, "took " + took + "ms to make a sound");

            // and it has not synthesized the whole song to do it - that is the point
            assertTrue(player.getCushionSeconds() < 20, "cushion: " + player.getCushionSeconds());
        } finally {
            player.stop();
        }
    }

    @Test
    @DisplayName("play")
    @EnabledIf("isAvailable")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void testPlay() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);

            System.setProperty("vavi.sound.mfi.faith.gain", String.valueOf(3 * volume));
        }
        FaithType4Renderer.play(sequence(Paths.get(mld)), 0);
    }

    /** an MFi if vavi-sound will read it as one, an smf otherwise */
    static Sequence sequence(Path path) throws Exception {
        try {
            return vavi.sound.mfi.MfiSystem.toMidiSequence(vavi.sound.mfi.MfiSystem.getSequence(path.toFile()));
        } catch (Exception e) {
Debug.println("not an MFi (" + e.getMessage() + "), reading it as an smf");
            return MidiSystem.getSequence(path.toFile());
        }
    }

    static MidiEvent event(ShortMessage message, long tick) {
        return new MidiEvent(message, tick);
    }

    /** {@code frame} and the bytes of the message, as a line to compare against */
    static String show(int[] event) {
        StringBuilder sb = new StringBuilder().append(event[0]);
        for (int i = 1; i < event.length; i++) {
            sb.append(" %02x".formatted(event[i]));
        }
        return sb.toString();
    }

    /** what the renderer wrote, back as {@code frame} and the bytes of the message */
    static List<int[]> read(Path file, int frames) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertEquals("RT4\0", new String(magic, StandardCharsets.US_ASCII));
        buffer.getInt(); // the voice set
        assertEquals(frames, buffer.getInt());
        buffer.getInt(); // the gain
        int count = buffer.getInt();
        List<int[]> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int frame = buffer.getInt();
            int length = buffer.getInt();
            int[] event = new int[1 + length];
            event[0] = frame;
            for (int j = 0; j < length; j++) {
                event[1 + j] = buffer.get() & 0xff;
            }
            buffer.position(buffer.position() + (-length & 3));
            events.add(event);
        }
        assertEquals(0, buffer.remaining(), "nothing after the last event");
        return events;
    }
}
