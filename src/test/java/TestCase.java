/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.swing.JFrame;
import javax.swing.JScrollPane;

import vavi.apps.mfiPlayer.MfiPlayer;
import vavi.sound.smaf.SmafSynthesizer.SmafReceiver;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static vavi.sound.midi.MidiUtil.volume;


/**
 * TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-03-04 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
public class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static {
        System.setProperty("javax.sound.midi.Sequencer", "#Real Time Sequencer");
    }

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 10 * 1000;

    @Property(name = "synthesizer")
    String synthesizer = "#FMF262 MIDI Synthesizer";

    @Property(name = "vavi.test.volume")
    double volume = 0.2f;

    @Property(name = "vavi.test.volume.midi")
    float midiVolume = 0.2f;

    @Property
    String mmf = "src/test/resources/test.mid";

    @Property
    String pianoroll = "src/test/resources/test.mid";

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
        System.setProperty("javax.sound.midi.Synthesizer", synthesizer);
        System.setProperty("vavi.sound.mobile.AudioEngine.volume", String.valueOf(volume));

Debug.println("midiVolume: " + midiVolume + ", synthesizer: " + System.getProperty("javax.sound.midi.Synthesizer"));
Debug.println("adpcm volume: " + System.getProperty("vavi.sound.mobile.AudioEngine.volume"));
    }

    @Test
    @DisplayName("gui")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test() throws Exception {
        MfiPlayer.main(new String[0]);

        CountDownLatch cdl = new CountDownLatch(1);
        cdl.await();
    }

    @Test
    @DisplayName("SamfReceiver")
    void test0() throws Exception {
Debug.println(mmf);

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);

        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.getTransmitter().setReceiver(new SmafReceiver(synthesizer)); // add adpcm driver
        sequencer.open();
Debug.println("sequencer: " + sequencer + ", " + sequencer.getClass().getName());

        Path path = Paths.get(mmf);

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

        volume(synthesizer.getReceiver(), midiVolume);

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

    @Test
    @DisplayName("PianoRoll")
    void test2() throws Exception {
        CountDownLatch cdl = new CountDownLatch(1);
        JFrame frame = new JFrame();
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                cdl.countDown();
            }
        });
        Path path = Paths.get(pianoroll);
        Sequence seq = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(path)));
        PianoRollPane pianoRoll = new PianoRollPane(seq);
        pianoRoll.setPreferredSize(new Dimension(640, 400));
        JScrollPane sp = new JScrollPane(pianoRoll);
        frame.getContentPane().add(sp);
        frame.pack();
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // TODO kills test and window listener doesn't work

        cdl.await();
    }
}
