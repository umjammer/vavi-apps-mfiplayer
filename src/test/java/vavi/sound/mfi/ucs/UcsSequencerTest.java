package vavi.sound.mfi.ucs;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.Receiver;

import vavi.sound.mfi.MfiSystem;
import vavi.sound.mfi.Sequence;
import vavi.sound.mfi.Sequencer;
import vavi.sound.mfi.Synthesizer;
import vavi.sound.mfi.vavi.panasonic.PanasonicSequencer;
import vavi.sound.mfi.vavi.sequencer.MachineDependentFunction;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.midi.MidiUtil.volume;


/** Tests UCS PCM packet decoding independently of an audio device. */
@PropsEntity(url = "file:local.properties")
class UcsSequencerTest {

    static boolean localPropertiesExists() {
        return Files.exists(Path.of("local.properties"));
    }

    @Property
    String mld = "src/test/resources/test.mld";

    @Property(name = "vavi.test.volume.midi")
    float volume = 0.2f;

    @BeforeEach
    void setupEach() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    void storesDefinedLengthAndSignedPcmPacket() throws Exception {
        PanasonicSequencer sequencer = new PanasonicSequencer(); // TODO vendor fixed

        sequencer.sequence(message(0x10, 3, 1, 0, 0, 3, 0, 0, 1, 0, 0, 3));
        sequencer.sequence(message(0x10, 3, 2, 0, 3, 0x80, 0x00, 0x7f));
        sequencer.sequence(message(0x11, 3, 2, 8, 1, 3, 0, 2, 0, 0x20, 0x34, 0x56));
        sequencer.sequence(message(0x12, 3));
        sequencer.sequence(message(0x40, 0x81, 0, 0x18));

        UcsSequencer.Wave wave = UcsSequencer.waveBank().wave(3);
        assertEquals(3, wave.length);
        assertEquals(1, wave.loopStart);
        assertEquals(3, wave.loopEnd);
        assertEquals(0x3456 / 256d, wave.rootPitch);
        assertEquals(24_000, wave.sampleRate);
        assertTrue(wave.enabled);
        assertArrayEquals(new byte[] { (byte) 0x80, 0, 0x7f }, wave.data);
        assertArrayEquals(new byte[] { (byte) 0x81, 0, 0x18 },
                          UcsSequencer.waveBank().part(0x81).data);
    }

    private static MachineDependentMessage message(int function, int... body) throws Exception {
        byte[] message = new byte[2 + body.length];
        message[0] = PanasonicSequencer.VENDOR_PANASONIC | MachineDependentFunction.CARRIER_DOCOMO;
        message[1] = (byte) function;
        for (int i = 0; i < body.length; i++) {
            message[i + 2] = (byte) body[i];
        }
        MachineDependentMessage result = new MachineDependentMessage();
        result.setMessage(0, message);
        return result;
    }

    @Test
    @DisplayName("UcsPreview")
    @Disabled("i have no samples")
    void test1() throws Exception {
Debug.print(mld);
        UcsPreview.main(new String[] {mld, "1"});
    }

    @Test
    @DisplayName("UcsSynthesizer")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test2() throws Exception {
        System.setProperty("vavi.sound.mfi.Synthesizer", "#Java MFi UCS Synthesizer");
        Synthesizer synthesizer = MfiSystem.getSynthesizer();
        assertInstanceOf(UcsSynthesizer.class, synthesizer);

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
Debug.println(meta.getType());
            if (meta.getType() == 47) cdl.countDown(); // TODO not coming
        });
Debug.println("START");
        sequencer.start();
        cdl.await();
Debug.println("END");
    }
}
