/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ucs;

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
import javax.sound.midi.SysexMessage;

import vavi.sound.faith.FaithRom;
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
 * UcsSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class UcsSynthesizerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean dllExists() {
        return FaithRom.isAvailable();
    }

    @Property
    String midi = "src/test/resources/test.mid";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static final boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static final long time = onIde ? 1000 * 1000 : 5 * 1000;

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
            if (i.getName().equals("UCS MIDI Synthesizer")) info = i;
        }
        assertNotNull(info);
        Synthesizer synthesizer = assertInstanceOf(UcsSynthesizer.class, MidiSystem.getMidiDevice(info));
        assertEquals(48, synthesizer.getMaxPolyphony());
    }

    @Test
    @EnabledIf("dllExists")
    void openAndClose() throws Exception {
        UcsSynthesizer synthesizer = new UcsSynthesizer();
        synthesizer.open();
        assertTrue(synthesizer.isOpen());
        MidiChannel channel = synthesizer.getChannels()[0];
        channel.programChange(0x79 << 7, 5);
        assertEquals(5, channel.getProgram());
        Receiver receiver = synthesizer.getReceiver();
        assertEquals(1, synthesizer.getReceivers().size());
        byte[] gmOn = {(byte) 0xf0, 0x7e, 0x7f, 0x09, 0x01, (byte) 0xf7};
        receiver.send(new SysexMessage(gmOn, gmOn.length), -1);
        synthesizer.close();
        assertTrue(synthesizer.getReceivers().isEmpty());
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
        Synthesizer synthesizer = new UcsSynthesizer();
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
