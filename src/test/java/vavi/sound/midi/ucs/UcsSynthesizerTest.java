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
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;

import vavi.sound.mfi.ucs.UcsAudioEngine;
import vavi.sound.mfi.ucs.UcsSynthesizer.UcsReceiver;
import vavi.sound.midi.MidiConstants;
import vavi.sound.midi.faith.FaithSynthesizer;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static vavi.sound.midi.MidiUtil.volume;


/**
 * UcsSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-17 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class UcsSynthesizerTest {

    static final String NAME = "Java MFi UCS Synthesizer";

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
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

        System.setProperty("javax.sound.midi.Synthesizer", "#Gervill");
        System.setProperty("vavi.sound.mfi.Synthesizer", "#" + NAME);
Debug.println("volume: " + volume);
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
        synthesizer.open();
        Receiver receiver = new UcsReceiver(new UcsAudioEngine());
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
    }
}
