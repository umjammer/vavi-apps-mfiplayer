/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.Receiver;

import vavi.sound.ma7.Ma7Rom;
import vavi.sound.mfi.MfiSystem;
import vavi.sound.mfi.Sequence;
import vavi.sound.mfi.Sequencer;
import vavi.sound.mfi.Synthesizer;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static vavi.sound.midi.MidiUtil.volume;


/**
 * Ma7MfiSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class Ma7MfiSynthesizerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean soExists() {
        return Ma7Rom.isAvailable();
    }

    @Property
    String mld = "src/test/resources/test.mld";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static final boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static final long time = onIde ? 1000 * 1000 : 10 * 1000;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        mld = System.getProperty("mld", mld);
    }

    /** the provider offers it by its name */
    @Test
    void provided() throws Exception {
        System.setProperty("vavi.sound.mfi.Synthesizer", "#Java MFi MA-7 Synthesizer");
        try {
            assertInstanceOf(Ma7MfiSynthesizer.class, MfiSystem.getSynthesizer());
        } finally {
            System.clearProperty("vavi.sound.mfi.Synthesizer");
        }
    }

    @Test
    @EnabledIf("soExists")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void play() throws Exception {
        System.setProperty("vavi.sound.mfi.Synthesizer", "#Java MFi MA-7 Synthesizer");
        Synthesizer synthesizer = MfiSystem.getSynthesizer();
        System.clearProperty("vavi.sound.mfi.Synthesizer");
        assertInstanceOf(Ma7MfiSynthesizer.class, synthesizer);

        Sequencer sequencer = MfiSystem.getSequencer();
        sequencer.open();
Debug.println(mld);
        Sequence sequence = MfiSystem.getSequence(new BufferedInputStream(Files.newInputStream(Path.of(mld))));
        synthesizer.open();
        Receiver receiver = synthesizer.getReceiver();
        sequencer.getTransmitter().setReceiver(receiver);
        volume(receiver, volume);
        sequencer.setSequence(sequence);
        CountDownLatch cdl = new CountDownLatch(1);
        sequencer.addMetaEventListener(meta -> {
            if (meta.getType() == 47) cdl.countDown();
        });
        sequencer.start();
        cdl.await(time, TimeUnit.MILLISECONDS);
        sequencer.stop();
        sequencer.close();
        synthesizer.close();
    }
}
