package vavi.sound.mfi.ucs;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.Receiver;

import vavi.sound.mfi.MfiSystem;
import vavi.sound.mfi.Sequence;
import vavi.sound.mfi.Sequencer;
import vavi.sound.mfi.Synthesizer;
import vavi.sound.mfi.Track;
import vavi.sound.mfi.vavi.sequencer.MachineDependentFunction;
import vavi.sound.mfi.vavi.sequencer.MachineDependentSequencer;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
        storesDefinedLengthAndSignedPcmPacket(UcsFunction.VENDOR_SHARP);
        storesDefinedLengthAndSignedPcmPacket(UcsFunction.VENDOR_PANASONIC);
    }

    private static void storesDefinedLengthAndSignedPcmPacket(int vendor) throws Exception {
        UcsSequencer.waveBank().clear();
        MachineDependentSequencer sequencer = MachineDependentSequencer.Factory.getSequencer(vendor | MachineDependentFunction.CARRIER_DOCOMO);

        sequencer.sequence(message(vendor, 0x10, 3, 1, 0, 0, 3, 0, 0, 1, 0, 0, 3), null);
        sequencer.sequence(message(vendor, 0x10, 3, 2, 0, 3, 0x80, 0x00, 0x7f), null);
        sequencer.sequence(message(vendor, 0x11, 3, 2, 8, 1, 3, 0, 2, 5, 0x15, 0x43, 0x40), null);
        sequencer.sequence(message(vendor, 0x12, 3, 0, 4, 0x80, 0, 2, 5), null);

        UcsSequencer.Wave wave = UcsSequencer.waveBank().wave(3);
        assertEquals(3, wave.length);
        assertEquals(1, wave.loopStart);
        assertEquals(3, wave.loopEnd);
        assertEquals(0x43, wave.rootPitch);
        assertEquals(32_000, wave.sampleRate);
        assertTrue(wave.enabled);
        assertArrayEquals(new byte[] { (byte) 0x80, 0, 0x7f }, wave.data);
        assertEquals(2, wave.bank);
        assertEquals(5, wave.program);
        assertEquals(List.of(wave), UcsSequencer.waveBank().tone(5));
        assertTrue(UcsSequencer.waveBank().tone(0).isEmpty());
    }

    private static MachineDependentMessage message(int vendor, int function, int... body) throws Exception {
        byte[] message = new byte[2 + body.length];
        message[0] = (byte) (vendor | MachineDependentFunction.CARRIER_DOCOMO);
        message[1] = (byte) function;
        for (int i = 0; i < body.length; i++) {
            message[i + 2] = (byte) body[i];
        }
        MachineDependentMessage result = new MachineDependentMessage();
        result.setMessage(0, message);
        return result;
    }

    @Test
    @DisplayName("fuetrek file: waves are assigned to the programs the song plays them at")
    @EnabledIf("judgmentExists")
    void assignsTones() throws Exception {
        UcsSequencer.waveBank().clear();
        MachineDependentSequencer sequencer = MachineDependentSequencer.Factory.getSequencer(UcsFunction.VENDOR_SHARP | MachineDependentFunction.CARRIER_DOCOMO);
        Sequence sequence = MfiSystem.getSequence(Path.of(judgment).toFile());
        Track track = sequence.getTracks()[0];
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MachineDependentMessage message && (message.getMessage()[6] & 0xf0) == 0x10) {
                sequencer.sequence(message, null);
            }
        }
        for (int program = 1; program <= 5; program++) {
            assertEquals(1, UcsSequencer.waveBank().tone(program).size(), "program " + program);
        }
        assertTrue(UcsSequencer.waveBank().tone(0).isEmpty());
        assertEquals(98, UcsSequencer.waveBank().wave(1).data.length);
    }

    static final String judgment = "tmp/ucs/Judgment_ft.mld";

    static boolean judgmentExists() {
        return Files.exists(Path.of(judgment));
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
//System.setProperty("vavi.sound.mobile.AudioEngine.volume", "0"); // adpcm off

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
