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
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.swing.JFrame;
import javax.swing.JScrollPane;

import vavi.apps.mfiPlayer.MfiPlayer;
import vavi.sound.mfi.MfiChip;
import vavi.sound.mfi.MfiChip.Condition;
import vavi.sound.mfi.MfiChip.Detection;
import vavi.sound.mfi.vavi.VaviMfiSynthesizer.VaviMfiReceiver;
import vavi.sound.midi.ma7.Ma7Synthesizer;
import vavi.sound.midi.rohm.RohmSynthesizer;
import vavi.sound.midi.ucs.UcsSynthesizer;
import vavi.sound.midi.ymf262.YmF262MidiDeviceProvider;
import vavi.sound.smaf.vavi.VaviSmafSynthesizer.VaviSmafReceiver;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;
import vavi.util.properties.annotation.PropsEntity.Util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
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
    String mld = "src/test/resources/test.mid";

    /**
     * A soundbank for the synthesizer to play, an MA-3 preset voice library (".vm3") being
     * one - which gives even a rom wave voice a timbre. Nothing named means the OPL3
     * (YMF262) bank, see NukedSynthesizer#SOUNDBANK_KEY.
     */
    @Property
    String soundbank = "";

    @Property
    String pianoroll = "src/test/resources/test.mid";

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            Util.bind(this);
        }
        System.setProperty("javax.sound.midi.Synthesizer", synthesizer);
        System.setProperty("vavi.sound.mobile.AudioEngine.volume", String.valueOf(volume));
        // a -D of the property wins, so that the OPL3 bank can be heard without editing this
        if (!soundbank.isEmpty() && System.getProperty(YmF262MidiDeviceProvider.SOUNDBANK_KEY) == null) {
            System.setProperty(YmF262MidiDeviceProvider.SOUNDBANK_KEY, soundbank);
        }

Debug.println("midiVolume: " + midiVolume + ", synthesizer: " + System.getProperty("javax.sound.midi.Synthesizer"));
Debug.println("adpcm volume: " + System.getProperty("vavi.sound.mobile.AudioEngine.volume"));
Debug.println("soundbank: " + System.getProperty(YmF262MidiDeviceProvider.SOUNDBANK_KEY));
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
    @DisplayName("samf: use AudioEngine receiver")
    @DisabledIfEnvironmentVariable(named = "GITHUB_WORKFLOW", matches = ".*")
    void test0() throws Exception {
Debug.println(mmf);

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);

        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.getTransmitter().setReceiver(new VaviSmafReceiver(synthesizer)); // use AudioEngine adpcm driver
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
    @DisplayName("smaf: use the original receiver")
    @DisabledIfEnvironmentVariable(named = "GITHUB_WORKFLOW", matches = ".*")
    void test01() throws Exception {
Debug.println(mmf);
        System.setProperty("vavi.sound.mobile.AudioEngine.disabled", "true");

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);

        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
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

        System.clearProperty("vavi.sound.mobile.AudioEngine.disabled");
    }

    @Test
    @DisplayName("mfi: use AudioEngine receiver")
    @DisabledIfEnvironmentVariable(named = "GITHUB_WORKFLOW", matches = ".*")
    void test1() throws Exception {
Debug.println(mld);

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);

        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.getTransmitter().setReceiver(new VaviMfiReceiver(synthesizer)); // use AudioEngine adpcm driver
        sequencer.open();
Debug.println("sequencer: " + sequencer + ", " + sequencer.getClass().getName());

        Path path = Paths.get(mld);

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
    @DisplayName("mfi: use the original receiver")
    @DisabledIfEnvironmentVariable(named = "GITHUB_WORKFLOW", matches = ".*")
    void test11() throws Exception {
Debug.println(mld);
        System.setProperty("vavi.sound.mobile.AudioEngine.disabled", "true");

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);

        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
        sequencer.open();
Debug.println("sequencer: " + sequencer + ", " + sequencer.getClass().getName());

        Path path = Paths.get(mld);

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

        System.clearProperty("vavi.sound.mobile.AudioEngine.disabled");
    }

    @Test
    @DisplayName("mfi: auto detection")
    @DisabledIfEnvironmentVariable(named = "GITHUB_WORKFLOW", matches = ".*")
    void test3() throws Exception {
Debug.println(mld);
        System.setProperty("vavi.sound.mobile.AudioEngine.disabled", "true");
        System.setProperty("javax.sound.midi.Synthesizer", "#Gervill");

        Path path = Paths.get(mld);
        Sequence seq = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(path)));

        Condition condition = Condition.create(seq);
        Detection detection = MfiChip.detect(condition);
        MfiChip chip = detection.chip();
Debug.print(detection.reason() + " -> " + detection.chip());

        Synthesizer synthesizer = switch (chip) {
            case YAMAHA -> new Ma7Synthesizer();
            case FUETREK -> new UcsSynthesizer();
            case ROHM -> new RohmSynthesizer();
        };
        synthesizer.open();
Debug.println("synthesizer: " + synthesizer);
        Receiver receiver = synthesizer.getReceiver();

        Sequencer sequencer = MidiSystem.getSequencer(false);
        sequencer.getTransmitter().setReceiver(receiver);
        sequencer.open();
Debug.println("sequencer: " + sequencer + ", " + sequencer.getClass().getName());

        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + meta.getType());
            if (meta.getType() == 47) cdl.countDown();
        };
        sequencer.setSequence(seq);
        sequencer.addMetaEventListener(mel);
Debug.println("START");
        sequencer.start();

        volume(receiver, midiVolume);

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

        System.clearProperty("vavi.sound.mobile.AudioEngine.disabled");
    }

    @Test
    @DisplayName("PianoRoll")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
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
