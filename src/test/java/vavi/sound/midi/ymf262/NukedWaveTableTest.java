/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.midi.MidiUtil.encode87;


/**
 * Tests the wave table (WT) voices an MFi file sends as SMAF exclusives.
 * <p>
 * Binding a wave opens an audio line, hence the CI guard.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-11 nsano initial version <br>
 * @see NukedWaveTable
 */
@DisabledIfEnvironmentVariable(named = "GITHUB_WORKFLOW", matches = ".*")
class NukedWaveTableTest {

    /** the 16 byte VM35 PCM voice of a 12000Hz wave 0 one shot */
    private static byte[] pcmVoice(int endPoint, int waveId) {
        byte[] voice = new byte[16];
        voice[0] = (byte) (12000 >> 8);      // Fs(MSB)
        voice[1] = (byte) (12000 & 0xff);    // Fs(LSB)
        voice[13] = (byte) (endPoint >> 8);  // EP(H)
        voice[14] = (byte) (endPoint & 0xff);// EP(L)
        voice[15] = (byte) (waveId & 0x7f);  // RM = 0, WaveID
        return voice;
    }

    /** 43 79 07 7f 01 mm ll pc dn vt &lt;voice&gt; f7 */
    private static byte[] voiceExclusive(int pc, int drumNote, int voiceType, byte[] voice) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {0x43, 0x79, 0x07, 0x7f, 0x01, 0x00, 0x00, (byte) pc, (byte) drumNote, (byte) voiceType});
        b.writeBytes(voice);
        b.write(0xf7);
        return b.toByteArray();
    }

    /** 43 05 00 &lt;wave id&gt; &lt;adpcm&gt; f7 */
    private static byte[] waveExclusive(int waveId, byte[] adpcm) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {0x43, 0x05, 0x00, (byte) waveId});
        b.writeBytes(adpcm);
        b.write(0xf7);
        return b.toByteArray();
    }

    /** 45 7f &lt;encode87(exclusive)&gt; f7, what SmafExclusive#pack of vavi-sound produces */
    private static byte[] pack(byte[] exclusive) {
        byte[] encoded = new byte[exclusive.length * 8 / 7 + 1];
        int encodedLength = encode87(exclusive, encoded, 0, exclusive.length);
        byte[] data = new byte[2 + encodedLength + 1];
        data[0] = 0x45;
        data[1] = 0x7f;
        System.arraycopy(encoded, 0, data, 2, encodedLength);
        data[data.length - 1] = exclusive[exclusive.length - 1];
        return data;
    }

    /** a melody wave table voice is claimed once its wave has arrived */
    @Test
    void melodyVoice() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        // the program a bank 1 / program 1 MFi tone collapses to
        synthesizer.processYamahaSmafSysexMessage(pack(voiceExclusive(0x41, 0, 1, pcmVoice(256, 0))));
        waveTable.programChange(0, 0x41);
        // the voice is registered but its wave is not here yet
        assertFalse(waveTable.claims(0, 60));

        synthesizer.processYamahaSmafSysexMessage(pack(waveExclusive(0, new byte[128])));
        assertTrue(waveTable.claims(0, 60));
        // another program on the same channel is still the OPL3's
        waveTable.programChange(0, 0x42);
        assertFalse(waveTable.claims(0, 60));
    }

    /** a drum voice is keyed by its note on channel 9, like NukedSynthesizer#registerVoice */
    @Test
    void drumVoice() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        synthesizer.processYamahaSmafSysexMessage(pack(waveExclusive(3, new byte[64])));
        synthesizer.processYamahaSmafSysexMessage(pack(voiceExclusive(1, 36, 1, pcmVoice(128, 3))));

        assertTrue(waveTable.claims(9, 36));
        assertFalse(waveTable.claims(9, 37));
        // note on hands the wave to the adpcm engine instead of the OPL3
        assertTrue(waveTable.noteOn(9, 36, 100));
        assertFalse(waveTable.noteOn(9, 37, 100));

        waveTable.close();
    }

    /** an FM voice is still a timbre, the wave table must not take it */
    @Test
    void fmVoiceIsNotWaveTable() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        byte[] fm = new byte[17];
        fm[2] = 0x40;   // LFO, PE, ALG
        synthesizer.processYamahaSmafSysexMessage(pack(voiceExclusive(0x10, 0, 0, fm)));
        waveTable.programChange(0, 0x10);

        assertFalse(waveTable.claims(0, 60));
    }

    /**
     * The 8 -> 7 bit unpacking must give the exclusive back byte for byte, at every
     * block alignment and with the 8th bit of every byte set - that bit is the whole
     * point of the packing, and it lives in the flag byte at the end of each block of
     * 7. Cutting the encoded range one byte short reads the last block's flags out of
     * a data byte, which leaves stray 0x80s in the tail.
     */
    @Test
    void unpackingIsExact() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();

        for (int length = 1; length <= 40; length++) {
            byte[] adpcm = new byte[length];
            for (int i = 0; i < length; i++) {
                adpcm[i] = (byte) (0x80 | (i & 0x7f)); // the 8th bit is what the packing carries
            }
            int waveId = length % 128;

            synthesizer.processYamahaSmafSysexMessage(pack(waveExclusive(waveId, adpcm)));

            assertArrayEquals(adpcm, synthesizer.getWaveTable().getWave(waveId), "adpcm length " + length);
        }
    }

    /** a preset (rom) wave has no data here, so the voice is never claimed */
    @Test
    void romWaveVoice() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        byte[] voice = pcmVoice(256, 0);
        voice[15] = (byte) 0x80;    // RM = 1
        synthesizer.processYamahaSmafSysexMessage(pack(voiceExclusive(0x20, 0, 1, voice)));
        waveTable.programChange(0, 0x20);

        assertFalse(waveTable.claims(0, 60));
    }
}
