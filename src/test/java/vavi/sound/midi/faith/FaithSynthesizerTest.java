/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.faith;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mfi.faith.FaithType4Device;
import vavi.sound.midi.MidiConstants;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * FaithSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class FaithSynthesizerTest {

    static final String NAME = "Faith Type4 MIDI Synthesizer";

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    /** the dll, and somewhere for what it plays to go */
    static boolean isPlayable() {
        return FaithType4Device.isAvailable() && AudioSystem.isLineSupported(
                new DataLine.Info(SourceDataLine.class, FaithType4Device.audioFormat()));
    }

    @Property
    String mld = "src/test/resources/test.mld";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 5 * 1000;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }

        System.setProperty("javax.sound.midi.Synthesizer", "#" + NAME);
Debug.println("volume: " + volume);
        System.setProperty("vavi.sound.mfi.faith.gain", String.valueOf(3 * volume)); // TODO do inside synthe
    }

    @Test
    @DisplayName("the spi offers it, dll or no dll")
    void testProvider() throws Exception {
        MidiDevice.Info info = Arrays.stream(MidiSystem.getMidiDeviceInfo())
                .filter(i -> i.getName().equals(NAME))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no " + NAME + " among " + Arrays.toString(MidiSystem.getMidiDeviceInfo())));

        MidiDevice device = MidiSystem.getMidiDevice(info);
        assertInstanceOf(FaithSynthesizer.class, device);
        Synthesizer synthesizer = (Synthesizer) device;

        assertSame(info, synthesizer.getDeviceInfo());
        assertEquals("vavi", info.getVendor());
        assertFalse(synthesizer.isOpen(), "it should not open itself");
        assertEquals(16, synthesizer.getChannels().length);
        assertTrue(synthesizer.getMaxPolyphony() >= 48);
        assertTrue(synthesizer.getLatency() > 0);
        assertEquals(0, synthesizer.getMaxTransmitters());
        assertThrows(MidiUnavailableException.class, synthesizer::getTransmitter);

        // the dll keeps its voices to itself, and says so rather than pretending to have none
        assertNull(synthesizer.getDefaultSoundbank());
        assertEquals(0, synthesizer.getAvailableInstruments().length);

        // and a device that is not mine is not mine
        MidiDevice.Info other = Arrays.stream(MidiSystem.getMidiDeviceInfo())
                .filter(i -> !i.getName().equals(NAME)).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> new FaithMidiDeviceProvider().getDevice(other));
    }

    @Test
    @DisplayName("a sequencer plays a song through it, in time, all the way to the end")
    @EnabledIf("isPlayable")
    void testPlaysASequence() throws Exception {
        FaithSynthesizer synthesizer = (FaithSynthesizer) MidiSystem.getMidiDevice(
                Arrays.stream(MidiSystem.getMidiDeviceInfo())
                        .filter(i -> i.getName().equals(NAME)).findFirst().orElseThrow());
        synthesizer.open();
        try {
            assertTrue(synthesizer.isOpen());
            var receiver = synthesizer.getReceiver();
            assertEquals(1, synthesizer.getReceivers().size());

            // this is a test, not a concert
            receiver.send(masterVolume(volume), -1);

            Sequence sequence = scale();
            Sequencer sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            try {
                sequencer.getTransmitter().setReceiver(receiver);
                sequencer.setSequence(sequence);
                long start = System.currentTimeMillis();
                sequencer.start();

                int loudest = 0;
                while (System.currentTimeMillis() - start < time) {
                    loudest = Math.max(loudest, synthesizer.getVoiceStatus().length);
                    Thread.sleep(50);
                }
                long played = System.currentTimeMillis() - start;

                FaithType4Device device = synthesizer.getDevice();
Debug.println("%d voices at once, %s".formatted(loudest, device.getStatistics()));
                assertTrue(loudest > 0, "nothing ever sounded");

                // it played for as long as it was left playing, which is what says it kept time
                long frames = device.getConsumedFrames();
                long expected = played * 44_100 / 1000;
                assertTrue(frames > expected * 9 / 10 && frames < expected * 11 / 10,
                        "%d frames in %dms, which wanted about %d".formatted(frames, played, expected));

                // and it did it without the emulator running out of road
                assertTrue(device.getStarvedFrames() < 44_100 / 10,
                        "%.3fs of silence for want of audio".formatted(
                                device.getStarvedFrames() / 44_100.0));
                assertEquals(0, device.getProblems().size(), () -> device.getLog().toString());
            } finally {
                sequencer.stop();
                sequencer.close();
            }
        } finally {
            synthesizer.close();
        }
        assertFalse(synthesizer.isOpen(), "it should have closed");
        assertEquals(0, synthesizer.getVoiceStatus().length, "notes left sounding");
    }

    /** a scale and a chord, at the default tempo, which is two and a half seconds of song */
    static Sequence scale() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 0, 0), 0));
        int[] notes = {60, 62, 64, 65, 67, 69, 71, 72};
        for (int i = 0; i < notes.length; i++) {
            long on = i * 240L;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, notes[i], 110), on));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, notes[i], 0), on + 160));
        }
        for (int note : new int[] {60, 64, 67}) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, note, 110), 1920));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, note, 0), 2400));
        }
        return sequence;
    }

    /** Universal Realtime, Device Control, Master Volume */
    static SysexMessage masterVolume(double gain) throws Exception {
        int value = (int) (gain * 16383);
        return new SysexMessage(new byte[] {
                (byte) 0xf0, 0x7f, 0x7f, 0x04, 0x01,
                (byte) (value & 0x7f), (byte) ((value >> 7) & 0x7f), (byte) 0xf7}, 8);
    }

    static <T> void assertInstanceOf(Class<T> type, Object value) {
        assertNotNull(value);
        assertTrue(type.isInstance(value), value.getClass() + " is not a " + type);
    }

    @Test
    @DisplayName("play mld")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
Debug.println(mld + ", " + Files.exists(Path.of(mld)));
        Sequence sequence = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(mld))));

        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + MidiConstants.MetaEvent.valueOf(meta.getType()));
            if (meta.getType() == 47) cdl.countDown();
        };
        Sequencer sequencer = MidiSystem.getSequencer(false);
Debug.println("sequencer: " + sequencer);
        sequencer.addMetaEventListener(mel);
        sequencer.open();
        Synthesizer synthesizer = MidiSystem.getSynthesizer();
Debug.println("synthesizer: " + synthesizer);
        assertInstanceOf(FaithSynthesizer.class, synthesizer);
        synthesizer.open();
        sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
        sequencer.setSequence(sequence);

        sequencer.start();
if (!onIde) {
 Thread.sleep(time);
 sequencer.stop();
 Debug.println("STOP");
} else {
        cdl.await();
}
        sequencer.removeMetaEventListener(mel);
        sequencer.close();
    }
}
