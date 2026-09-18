/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import vavi.util.Debug;

import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Renders the wave table (WT) voices and the stream PCM of "GuitarMan.mmf", an MA-5 demo,
 * the way {@link NukedSynthesizer} gets them with {@code vavi.sound.mobile.AudioEngine.disabled}
 * set: every voice, wave and stream as an exclusive in the midi sequence itself.
 * <p>
 * No audio line, the OPL3 part is left out: what is written to {@code tmp/GuitarMan_wt.wav}
 * is only what {@link NukedWaveTable} adds, to listen to.
 * </p>
 * <pre>
 *  ch 3   bank 7c/04 program 101, a WT voice of wave 1
 *  ch 9   bank 7d/00 kit 2, WT drums of wave 0 and 2 (and rom waves, see MaRomWaves#ROM_KEY)
 *  ch 15  bank 7d/00, stream PCM "Mwa1" ~ "Mwa10" on keys 0 ~ 9
 * </pre>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-13 nsano initial version <br>
 */
@EnabledIf("exists")
class GuitarManTest {

    static final Path mmf = Path.of("/usr/local/src/MA-3-MegaMod/ringtones/Yamaha MMF demos/GuitarMan.mmf");

    static boolean exists() {
        return Files.exists(mmf);
    }

    static final String DISABLED = "vavi.sound.mobile.AudioEngine.disabled";

    String old;

    @BeforeEach
    void setUp() {
        old = System.setProperty(DISABLED, "true");
    }

    @AfterEach
    void tearDown() {
        if (old == null) System.clearProperty(DISABLED); else System.setProperty(DISABLED, old);
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai") // TODO check
    void render() throws Exception {
        Sequence sequence = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(mmf)));

        List<MidiEvent> events = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) events.add(track.get(i));
        }
        events.sort(Comparator.comparingLong(MidiEvent::getTick)); // stable, keeps a tick's order

        int sampleRate = 44100;
        NukedWaveTable waveTable = new NukedWaveTable(sampleRate);
        YamahaVoices voices = new YamahaVoices((bank, program, voice) -> {}, waveTable);

        long mpq = 500_000; // micro seconds per quarter note
        long tick = 0;
        double samples = 0;
        int[] claimed = new int[16];
        int streams = 0, exclusives = 0;
        ByteBuffer out = ByteBuffer.allocate(sampleRate * 4 * 60).order(ByteOrder.LITTLE_ENDIAN);
        int[][] buffer = new int[2][sampleRate];

        for (MidiEvent event : events) {
            // render up to this event
            samples += (event.getTick() - tick) * mpq / 1_000_000.0 / sequence.getResolution() * sampleRate;
            tick = event.getTick();
            while (samples >= 1 && out.remaining() >= 4) {
                int n = (int) Math.min(samples, Math.min(buffer[0].length, out.remaining() / 4));
                for (int[] b : buffer) java.util.Arrays.fill(b, 0, n, 0);
                waveTable.render(buffer, n);
                for (int i = 0; i < n; i++) {
                    out.putShort((short) buffer[0][i]);
                    out.putShort((short) buffer[1][i]);
                }
                samples -= n;
            }

            MidiMessage message = event.getMessage();
            switch (message) {
                case MetaMessage meta when meta.getType() == 0x51 -> {
                    byte[] d = meta.getData();
                    mpq = ((d[0] & 0xff) << 16) | ((d[1] & 0xff) << 8) | (d[2] & 0xff);
                }
                case SysexMessage sysex -> {
                    exclusives++;
                    voices.process(sysex.getData());
                }
                case ShortMessage m -> {
                    int ch = m.getChannel();
                    switch (m.getCommand()) {
                        case ShortMessage.NOTE_ON -> {
                            if (m.getData2() > 0) {
                                if (waveTable.noteOn(ch, m.getData1(), m.getData2())) {
                                    claimed[ch]++;
                                    if (ch == 15) streams++;
                                }
                            } else {
                                waveTable.noteOff(ch, m.getData1());
                            }
                        }
                        case ShortMessage.NOTE_OFF -> waveTable.noteOff(ch, m.getData1());
                        case ShortMessage.CONTROL_CHANGE -> waveTable.controlChange(ch, m.getData1(), m.getData2());
                        case ShortMessage.PROGRAM_CHANGE -> waveTable.programChange(ch, m.getData1());
                        case ShortMessage.PITCH_BEND -> waveTable.pitchBend(ch, m.getData1() | (m.getData2() << 7));
                        default -> {}
                    }
                }
                default -> {}
            }
        }
        // the tail
        for (int t = 0; t < 2 && out.remaining() >= 4; t++) {
            int n = Math.min(buffer[0].length, out.remaining() / 4);
            for (int[] b : buffer) java.util.Arrays.fill(b, 0, n, 0);
            waveTable.render(buffer, n);
            for (int i = 0; i < n; i++) {
                out.putShort((short) buffer[0][i]);
                out.putShort((short) buffer[1][i]);
            }
        }

        double energy = 0;
        int frames = out.position() / 4;
        for (int i = 0; i < out.position(); i += 2) energy += Math.pow(out.getShort(i), 2);
Debug.printf("exclusives: %d, frames: %d (%.1fs), rms: %.0f%n", exclusives, frames, frames / (double) sampleRate, Math.sqrt(energy / Math.max(1, frames * 2)));
        for (int ch = 0; ch < 16; ch++) {
            if (claimed[ch] > 0) Debug.println("ch " + ch + ": " + claimed[ch] + " wave table notes");
        }

        Path wav = Path.of("tmp", "GuitarMan_wt.wav");
        Files.createDirectories(wav.getParent());
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        byte[] bytes = java.util.Arrays.copyOf(out.array(), out.position());
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(bytes), format, frames), AudioFileFormat.Type.WAVE, wav.toFile());
Debug.println("wrote " + wav.toAbsolutePath());

        assertTrue(claimed[3] > 0, "the WT melody voice of ch 3");
        assertTrue(claimed[9] > 0, "the WT drums of ch 9");
        assertTrue(streams > 0, "the stream PCM of ch 15");
        assertTrue(energy > 0, "it sounds");
    }
}
