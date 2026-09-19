/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.rohm;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;

import vavi.sound.mfi.rohm.RohmRom;
import vavi.sound.midi.MidiConstants;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.midi.MidiUtil.volume;


/**
 * RohmSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class RohmSynthesizerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean dllExists() {
        return RohmRom.isAvailable();
    }

    @Property
    String midi = "src/test/resources/test.mid";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 5 * 1000;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    /** the provider offers it by its name, whether or not the dll is there */
    @Test
    void provided() throws Exception {
        MidiDevice.Info info = null;
        for (MidiDevice.Info i : MidiSystem.getMidiDeviceInfo()) {
            if (i.getName().equals("Rohm MIDI Synthesizer")) info = i;
        }
        assertNotNull(info);
        Synthesizer synthesizer = assertInstanceOf(RohmSynthesizer.class, MidiSystem.getMidiDevice(info));
        assertEquals(64, synthesizer.getMaxPolyphony());
    }

    @Test
    @EnabledIf("dllExists")
    void openAndClose() throws Exception {
        RohmSynthesizer synthesizer = new RohmSynthesizer();
        synthesizer.open();
        assertTrue(synthesizer.isOpen());
        MidiChannel channel = synthesizer.getChannels()[0];
        channel.programChange(0x79 << 7, 5);
        assertEquals(5, channel.getProgram());
        synthesizer.close();
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void play() throws Exception {
Debug.println(midi + ", " + Files.exists(Path.of(midi)));
        Sequence sequence = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(midi))));

        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + MidiConstants.MetaEvent.valueOf(meta.getType()));
            if (meta.getType() == 47) cdl.countDown();
        };
        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.addMetaEventListener(mel);
        sequencer.open();
        Synthesizer synthesizer = new RohmSynthesizer();
        synthesizer.open();
        Receiver receiver = synthesizer.getReceiver();
        sequencer.getTransmitter().setReceiver(receiver);
        sequencer.setSequence(sequence);
        volume(receiver, volume);

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
        synthesizer.close();
    }
}
