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
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;

import vavi.sound.ma7.Ma7Rom;
import vavi.sound.midi.MidiConstants;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.AfterEach;
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
 * SmafMa7SynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class SmafMa7SynthesizerTest {

    static final String NAME = "SMAF MA-7 MIDI Synthesizer";

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean soExists() {
        return Ma7Rom.isAvailable();
    }

    /** a smaf song, read as a midi sequence by the file reader of vavi-sound */
    @Property
    String mmf = "src/test/resources/test.mid";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static final boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static final long time = onIde ? 1000 * 1000 : 10 * 1000;

    /**
     * The MA-7 plays the voices of the song itself, so a "Mobile Standard" song is to be converted
     * for one which does: its percussion channels keep their own channel and their own program,
     * which is the drum kit. See {@code Ma7SmafSynthesizer.Ma7SmafReceiver} for what the property
     * is and why it is not about the audio engines its name names.
     */
    static final String DISABLED = "vavi.sound.mobile.AudioEngine.disabled";

    /** how loud the stream waves of a song are against the sound source, see {@code AudioEngineMixer} */
    static final String ADPCM_VOLUME = "vavi.sound.mobile.AudioEngine.volume";

    String disabled;
    String adpcmVolume;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        mmf = System.getProperty("mmf", mmf);
        disabled = System.setProperty(DISABLED, "true");
        // the streams are mixed into the sound source's line, not played on a line of their own,
        // so they want to be level with it: the default 0.2 is what a line of their own would take
        adpcmVolume = System.setProperty(ADPCM_VOLUME, "1");
    }

    @AfterEach
    void teardownEach() {
        if (disabled == null) System.clearProperty(DISABLED); else System.setProperty(DISABLED, disabled);
        if (adpcmVolume == null) System.clearProperty(ADPCM_VOLUME); else System.setProperty(ADPCM_VOLUME, adpcmVolume);
    }

    /** the provider offers it by its name, whether or not the library is there */
    @Test
    void provided() throws Exception {
        MidiDevice.Info info = null;
        for (MidiDevice.Info i : MidiSystem.getMidiDeviceInfo()) {
            if (i.getName().equals(NAME)) info = i;
        }
        assertNotNull(info);
        Synthesizer synthesizer = assertInstanceOf(SmafMa7Synthesizer.class, MidiSystem.getMidiDevice(info));
        assertEquals(64, synthesizer.getMaxPolyphony());
    }

    @Test
    @EnabledIf("soExists")
    void openAndClose() throws Exception {
        SmafMa7Synthesizer synthesizer = new SmafMa7Synthesizer();
        synthesizer.open();
        assertTrue(synthesizer.isOpen());
        // a melody voice of the bank a smaf song selects (the bank select msb 0x7c)
        synthesizer.getChannels()[0].programChange(0x7c << 7, 5);
        assertEquals(5, synthesizer.getChannels()[0].getProgram());
        synthesizer.close();
    }

    @Test
    @EnabledIf("soExists")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
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
        Synthesizer synthesizer = new SmafMa7Synthesizer();
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
