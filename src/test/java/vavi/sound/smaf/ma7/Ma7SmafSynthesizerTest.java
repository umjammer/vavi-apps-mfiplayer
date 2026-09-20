/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma7;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.Receiver;

import vavi.sound.ma7.Ma7Rom;
import vavi.sound.smaf.MetaEventListener;
import vavi.sound.smaf.Sequence;
import vavi.sound.smaf.Sequencer;
import vavi.sound.smaf.SmafDevice;
import vavi.sound.smaf.SmafSystem;
import vavi.sound.smaf.Synthesizer;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static vavi.sound.midi.MidiUtil.volume;


/**
 * Ma7SmafSynthesizerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class Ma7SmafSynthesizerTest {

    static final String NAME = "Java SMAF MA-7 Synthesizer";

    /** the key of the property has a typo in vavi-sound ({@code vavi.sound.smaf.SmafSystem}) */
    static final String SYNTHESIZER_KEY = "vavi.sound.samf.Synthesizer";

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static boolean soExists() {
        return Ma7Rom.isAvailable();
    }

    @Property
    String mmf = "src/test/resources/test.mid";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    static final boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static final long time = onIde ? 1000 * 1000 : 10 * 1000;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        mmf = System.getProperty("mmf", mmf);
    }

    /** the provider offers it by its name */
    @Test
    void provided() throws Exception {
        SmafDevice.Info info = Arrays.stream(SmafSystem.getSmafDeviceInfo())
                .filter(i -> i.getName().equals(NAME)).findFirst().orElse(null);
        assertNotNull(info);
        assertInstanceOf(Ma7SmafSynthesizer.class, SmafSystem.getSmafDevice(info));

        System.setProperty(SYNTHESIZER_KEY, "#" + NAME);
        try {
            assertInstanceOf(Ma7SmafSynthesizer.class, SmafSystem.getSynthesizer());
        } finally {
            System.clearProperty(SYNTHESIZER_KEY);
        }
    }

    @Test
    @EnabledIf("soExists")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void play() throws Exception {
        System.setProperty(SYNTHESIZER_KEY, "#" + NAME);
        Synthesizer synthesizer = SmafSystem.getSynthesizer();
        System.clearProperty(SYNTHESIZER_KEY);
        assertInstanceOf(Ma7SmafSynthesizer.class, synthesizer);

        Sequencer sequencer = SmafSystem.getSequencer();
        sequencer.open();
Debug.println(mmf);
        Sequence sequence = SmafSystem.getSequence(Path.of(mmf).toFile());
        synthesizer.open();
        Receiver receiver = synthesizer.getReceiver();
        sequencer.getTransmitter().setReceiver(receiver);
        volume(receiver, volume);
        sequencer.setSequence(sequence);
        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
            if (meta.getType() == 47) cdl.countDown();
        };
        sequencer.addMetaEventListener(mel);
        sequencer.start();
        cdl.await(time, TimeUnit.MILLISECONDS);
        sequencer.stop();
        sequencer.removeMetaEventListener(mel);
        sequencer.close();
        synthesizer.close();
    }
}
