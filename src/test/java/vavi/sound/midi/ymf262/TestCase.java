/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static vavi.sound.midi.MidiUtil.volume;


/**
 * vavi.sound.midi.ymf262.TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-02-05 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
public class TestCase {

    static {
        System.setProperty("javax.sound.midi.Sequencer", "#Real Time Sequencer");
        System.setProperty("javax.sound.midi.Synthesizer", "#FMF262 MIDI Synthesizer");
    }

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 10 * 1000;

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    @Property(name = "opl3.test")
    String file = "src/test/resources/test.mid";

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    String ibk = "src/main/resources/opl3/gm.ibk";

    @Test
    @DisplayName("ibk reader")
    void test() throws Exception {
        IbkSoundbankReaderTest.main(new String[] {ibk, "inst_opl2"});
    }

    @Test
    @DisplayName("spi")
    void test0() throws Exception {
Debug.println(file);

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        assertEquals(MatsuokaSynthesizer.class, synthesizer.getClass());
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);

        Sequencer sequencer = MidiSystem.getSequencer(false);
        Receiver receiver = synthesizer.getReceiver();
        sequencer.getTransmitter().setReceiver(receiver);
        sequencer.open();
Debug.println("sequencer: " + sequencer + ", " + sequencer.getClass().getName());

        Path path = Paths.get(file);

        Sequence seq = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(path)));

        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + meta.getType());
            if (meta.getType() == 47) cdl.countDown();
        };
        sequencer.setSequence(seq);
        sequencer.addMetaEventListener(mel);
Debug.println("START");
        sequencer.start();

        volume(receiver, volume); // volume works?

if (!onIde) {
 Thread.sleep(time);
 sequencer.stop();
Debug.println("STOP");
} else {
            cdl.await();
}
Debug.println("END");
        sequencer.removeMetaEventListener(mel);
        sequencer.close();

        synthesizer.close();
    }
}
