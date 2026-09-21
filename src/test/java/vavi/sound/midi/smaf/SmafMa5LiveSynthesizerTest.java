/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.smaf;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;

import vavi.sound.midi.MidiConstants;
import vavi.sound.smaf.ma5.Ma5Device;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.midi.MidiUtil.volume;


/**
 * SmafMa5LiveSynthesizerTest.
 * <p>
 * {@code M5_EmuSmw5.dll} is where {@code -Dvavi.sound.smaf.ma5.path} says, default
 * {@code /usr/local/src/mmftool}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class SmafMa5LiveSynthesizerTest {

    static final String NAME = "SMAF MA-5 Live MIDI Synthesizer";

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean dllExists() {
        return Ma5Device.isAvailable();
    }

    static boolean dllAndSongExist() {
        return dllExists() && Files.exists(Path.of(song()));
    }

    /** a smaf song, read as a midi sequence by the file reader of vavi-sound */
    @Property
    String mmf = "src/test/resources/test.mid";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static final boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static final long time = onIde ? 1000 * 1000 : 8 * 1000;

    /** see {@link SmafMa7SynthesizerTest#DISABLED} */
    static final String DISABLED = "vavi.sound.mobile.AudioEngine.disabled";

    String disabled;

    /** the song, before the fields are bound: what {@link EnabledIf} asks about */
    static String song() {
        SmafMa5LiveSynthesizerTest test = new SmafMa5LiveSynthesizerTest();
        try {
            if (localPropertiesExists()) {
                PropsEntity.Util.bind(test);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return System.getProperty("mmf", test.mmf);
    }

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        mmf = System.getProperty("mmf", mmf);
        disabled = System.setProperty(DISABLED, "true");
    }

    @AfterEach
    void teardownEach() {
        if (disabled == null) System.clearProperty(DISABLED); else System.setProperty(DISABLED, disabled);
    }

    /** the provider offers it by its name, whether or not the dll is there */
    @Test
    void provided() throws Exception {
        MidiDevice.Info info = null;
        for (MidiDevice.Info i : MidiSystem.getMidiDeviceInfo()) {
            if (i.getName().equals(NAME)) info = i;
        }
        assertNotNull(info);
        Synthesizer synthesizer = assertInstanceOf(SmafMa5LiveSynthesizer.class, MidiSystem.getMidiDevice(info));
        assertEquals(32, synthesizer.getMaxPolyphony());
    }

    /** a few notes of the dll's own voices, by hand: it sounds, and it takes what it is sent */
    @Test
    @EnabledIf("dllExists")
    void notes() throws Exception {
        SmafMa5LiveSynthesizer synthesizer = new SmafMa5LiveSynthesizer();
        synthesizer.open();
        try {
            assertTrue(synthesizer.isOpen());
            volume(synthesizer.getReceiver(), volume);
            MidiChannel channel = synthesizer.getChannels()[0];
            // a melody voice of the bank a smaf song selects (the bank select msb 0x7c)
            channel.programChange(0x7c << 7, 0);
            assertEquals(0x7c, channel.getController(0));
            for (int note : new int[] {60, 64, 67, 72}) {
                channel.noteOn(note, 100);
                Thread.sleep(300);
                channel.noteOff(note);
            }
            Thread.sleep(500);
            Ma5Device device = synthesizer.getDevice();
Debug.println("latency: " + synthesizer.getLatency() / 1000 + "ms, peak: " + device.getPeak() + ", " + device.getStatistics());
Debug.println("log: " + device.getLog());
            assertTrue(device.getProblems().isEmpty(), device.getProblems().toString());
            assertTrue(device.getPeak() > 0, "silent");
        } finally {
            synthesizer.close();
        }
    }

    /** a song through a sequencer, the voices and the waves of it by exclusives */
    @Test
    @EnabledIf("dllAndSongExist")
    void play() throws Exception {
Debug.println(mmf + ", " + Files.exists(Path.of(mmf)));
        Sequence sequence = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(mmf))));

        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + MidiConstants.MetaEvent.valueOf(meta.getType()));
            if (meta.getType() == 47) cdl.countDown();
        };
        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.addMetaEventListener(mel);
        sequencer.open();
        SmafMa5LiveSynthesizer synthesizer = new SmafMa5LiveSynthesizer();
        synthesizer.open();
        try {
            Receiver receiver = synthesizer.getReceiver();
            sequencer.getTransmitter().setReceiver(receiver);
            sequencer.setSequence(sequence);
            volume(receiver, volume);

            sequencer.start();
            cdl.await(time, TimeUnit.MILLISECONDS);
            sequencer.stop();
Debug.println("STOP");
            sequencer.removeMetaEventListener(mel);
            sequencer.close();

            Ma5Device device = synthesizer.getDevice();
Debug.println("peak: " + device.getPeak() + ", " + device.getStatistics());
Debug.println("log: " + device.getLog());
            assertTrue(device.getPeak() > 0, "silent");
        } finally {
            synthesizer.close();
        }
    }
}
