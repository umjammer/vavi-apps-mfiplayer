/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.midi.MidiUtil.encode87;

import vavi.sound.mobile.StreamExclusive;


/**
 * Tests the wave table (WT) voices an MFi file sends as SMAF exclusives.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-11 nsano initial version <br>
 * @see NukedWaveTable
 */
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

    /** 43 05 02 bb pp &lt;16 byte VM35 PCM voice&gt; f7, a SMAF "EXVO" voice */
    private static byte[] smafVoiceExclusive(int bank, int program, byte[] voice) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {0x43, 0x05, 0x02, (byte) bank, (byte) program});
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
        synthesizer.getSmafVoices().process(pack(voiceExclusive(0x41, 0, 1, pcmVoice(256, 0))));
        waveTable.programChange(0, 0x41);
        // the voice is registered but its wave is not here yet
        assertFalse(waveTable.claims(0, 60));

        synthesizer.getSmafVoices().process(pack(waveExclusive(0, new byte[128])));
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

        synthesizer.getSmafVoices().process(pack(waveExclusive(3, new byte[64])));
        synthesizer.getSmafVoices().process(pack(voiceExclusive(1, 36, 1, pcmVoice(128, 3))));

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
        synthesizer.getSmafVoices().process(pack(voiceExclusive(0x10, 0, 0, fm)));
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

            synthesizer.getSmafVoices().process(pack(waveExclusive(waveId, adpcm)));

            assertArrayEquals(adpcm, synthesizer.getWaveTable().getWave(waveId), "adpcm length " + length);
        }
    }

    /**
     * A SMAF "EXVO" voice needs no wave exclusive: its "EXWV" wave goes straight
     * into the SMAF wave engine, so the voice alone is enough to claim the patch.
     * This is the shape "tmp/yuvi/01.mmf" holds.
     */
    @Test
    void smafVoice() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        synthesizer.getSmafVoices().process(pack(smafVoiceExclusive(0x01, 0x00, pcmVoice(625, 0))));
        waveTable.programChange(0, 0x00);
        assertTrue(waveTable.claims(0, 60));

        // bit 7 of the bank marks a drum bank, the program is then the note
        synthesizer.getSmafVoices().process(pack(smafVoiceExclusive(0x81, 36, pcmVoice(128, 1))));
        assertTrue(waveTable.claims(9, 36));
        assertFalse(waveTable.claims(9, 37));
    }

    /** an unknown voice type byte must be ignored, not thrown on */
    @Test
    void unknownVoiceType() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();

        // 111 of the 43326 exclusives of a 1845 file smaf corpus have one
        synthesizer.getSmafVoices().process(pack(voiceExclusive(0x10, 0, 0x7f, new byte[17])));

        synthesizer.getWaveTable().programChange(0, 0x10);
        assertFalse(synthesizer.getWaveTable().claims(0, 60));
    }

    /** a preset (rom) wave has no data here, so the voice is never claimed */
    @Test
    void romWaveVoice() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        byte[] voice = pcmVoice(256, 0);
        voice[15] = (byte) 0x80;    // RM = 1
        synthesizer.getSmafVoices().process(pack(voiceExclusive(0x20, 0, 1, voice)));
        waveTable.programChange(0, 0x20);

        assertFalse(waveTable.claims(0, 60));
    }

    /** the 16 byte VM35 PCM voice of a looping, sustaining one */
    private static byte[] sustainingVoice(int fs, int loopPoint, int endPoint, int waveId) {
        byte[] voice = pcmVoice(endPoint, waveId);
        voice[0] = (byte) (fs >> 8);
        voice[1] = (byte) fs;
        voice[5] = (byte) 0x80;              // RR 8, DR 0
        voice[6] = (byte) 0xf0;              // AR 15, SL 0
        voice[11] = (byte) (loopPoint >> 8);
        voice[12] = (byte) loopPoint;
        return voice;
    }

    /** 43 79 07 7f 01 mm ll pc dn vt &lt;voice&gt; f7 with a bank */
    private static byte[] voiceExclusive(int bankMSB, int bankLSB, int pc, int drumNote, int voiceType, byte[] voice) {
        byte[] exclusive = voiceExclusive(pc, drumNote, voiceType, voice);
        exclusive[5] = (byte) bankMSB;
        exclusive[6] = (byte) bankLSB;
        return exclusive;
    }

    /** 43 79 07 7f 03 id 00 &lt;adpcm&gt; f7, MA-5 SetWave */
    private static byte[] setWaveExclusive(int waveId, byte[] adpcm) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {0x43, 0x79, 0x07, 0x7f, 0x03, (byte) waveId, 0x00});
        b.writeBytes(adpcm);
        b.write(0xf7);
        return b.toByteArray();
    }

    /** some adpcm which does not decode to silence */
    private static byte[] noise(int length) {
        byte[] adpcm = new byte[length];
        for (int i = 0; i < length; i++) {
            adpcm[i] = (byte) (i % 2 == 0 ? 0x77 : 0x88);
        }
        return adpcm;
    }

    /** the peak of what the wave table adds to silence */
    private static int render(NukedWaveTable waveTable, int length) {
        int[][] buffer = new int[2][length];
        waveTable.render(buffer, length);
        int peak = 0;
        for (int[] channel : buffer) {
            for (int sample : channel) {
                peak = Math.max(peak, Math.abs(sample));
            }
        }
        return peak;
    }

    /**
     * A SMAF file tells its voices apart by the bank: "GuitarMan.mmf" has an FM voice and a
     * WT one both of program 101, in bank LSB 1 and 4, see Bank_Program3 of the MA-3 driver.
     */
    @Test
    void smafBank() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        synthesizer.getSmafVoices().process(pack(setWaveExclusive(1, new byte[128])));
        synthesizer.getSmafVoices().process(pack(voiceExclusive(0x7c, 4, 101, 0, 1, pcmVoice(255, 1))));

        waveTable.controlChange(3, 0, 0x7c);
        waveTable.controlChange(3, 32, 4);
        waveTable.programChange(3, 101);
        assertTrue(waveTable.claims(3, 60));

        // the same program in another bank is not this voice
        waveTable.controlChange(3, 32, 5);
        assertFalse(waveTable.claims(3, 60));
    }

    /** a drum voice of a smaf drum bank is (kit, note), and any channel can be a drum one */
    @Test
    void smafDrumBank() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        synthesizer.getSmafVoices().process(pack(setWaveExclusive(0, new byte[128])));
        synthesizer.getSmafVoices().process(pack(voiceExclusive(0x7d, 0, 2, 71, 1, pcmVoice(255, 0))));

        waveTable.controlChange(5, 0, 0x7d);
        waveTable.controlChange(5, 32, 0);
        waveTable.programChange(5, 2);
        assertTrue(waveTable.claims(5, 71));
        assertFalse(waveTable.claims(5, 72));
        // another kit
        waveTable.programChange(5, 3);
        assertFalse(waveTable.claims(5, 71));
    }

    /** the MA-3 exclusives are 7 bit encoded, the flag byte first, see Decode_7bitData */
    @Test
    void ma3Decoding() {
        // the example of mmftool's memo/wt.txt
        byte[] encoded = {0x53, 0x00, 0x7f, 0x00, 0x7f, 0x7e, 0x09, 0x0b};
        byte[] expected = {(byte) 0x80, 0x7f, (byte) 0x80, 0x7f, 0x7e, (byte) 0x89, (byte) 0x8b};
        assertArrayEquals(expected, YamahaVoices.decodeMa3(encoded, 0, encoded.length));

        // a short last block
        assertArrayEquals(new byte[] {(byte) 0x81, 0x02}, YamahaVoices.decodeMa3(new byte[] {0x40, 0x01, 0x02}, 0, 3));
    }

    /** an MA-3 SetWave carries the wave 7 bit encoded */
    @Test
    void ma3SetWave() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();

        // 43 79 06 7f 03 id 00 <encoded> f7, no packing needed, it is 7 bit safe
        byte[] exclusive = {0x43, 0x79, 0x06, 0x7f, 0x03, 0x02, 0x00, 0x53, 0x00, 0x7f, 0x00, 0x7f, 0x7e, 0x09, 0x0b, (byte) 0xf7};
        assertTrue(synthesizer.getSmafVoices().process(exclusive));

        byte[] expected = {(byte) 0x80, 0x7f, (byte) 0x80, 0x7f, 0x7e, (byte) 0x89, (byte) 0x8b};
        assertArrayEquals(expected, synthesizer.getWaveTable().getWave(2));
    }

    /** a voice sounds, loops while the key is held and fades out after the key off */
    @Test
    void envelopeAndLoop() {
        NukedWaveTable waveTable = new NukedWaveTable(44100);
        waveTable.setWave(0, noise(512));
        waveTable.setVoice(0, 0, 10, 0, sustainingVoice(8000, 100, 1000, 0));
        waveTable.programChange(0, 10);

        assertTrue(waveTable.noteOn(0, 60, 127));
        assertTrue(render(waveTable, 4410) > 0);
        // 1001 samples at 8kHz are 0.125s, a looping voice still sounds after a second
        for (int i = 0; i < 10; i++) render(waveTable, 4410);
        assertEquals(1, waveTable.getPlayers());

        assertTrue(waveTable.noteOff(0, 60));
        // RR 8 is 39280.64 / 128 = 307ms for 96dB
        for (int i = 0; i < 5; i++) render(waveTable, 4410);
        assertEquals(0, waveTable.getPlayers());
    }

    /** a one shot ends with its wave */
    @Test
    void oneShot() {
        NukedWaveTable waveTable = new NukedWaveTable(44100);
        waveTable.setWave(0, noise(512));
        byte[] voice = sustainingVoice(44100, 1000, 1000, 0); // LP = EP, no loop
        waveTable.setVoice(0, 0, 1, 36, voice);

        assertTrue(waveTable.noteOn(9, 36, 100));
        render(waveTable, 1000);
        assertEquals(1, waveTable.getPlayers());
        render(waveTable, 100);
        assertEquals(0, waveTable.getPlayers(), "1001 samples at the output rate");
    }

    /** a note of key 0 ~ 12 on a drum channel is a stream, see Note_ON3 */
    @Test
    void streamNote() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();

        // "Mwa3" arrives as the stream exclusive of vavi-sound
        synthesizer.getSmafVoices().process(pack(StreamExclusive.wave(3, StreamExclusive.Format.ADPCM, 1, 4, 8000, noise(4000))));
        waveTable.controlChange(15, 0, 0x7d);
        waveTable.controlChange(15, 32, 0);
        waveTable.programChange(15, 0);

        assertTrue(waveTable.claims(15, 2));
        assertFalse(waveTable.claims(15, 3), "no Mwa4");
        assertFalse(waveTable.claims(0, 2), "not a drum channel");

        assertTrue(waveTable.noteOn(15, 2, 100));
        assertTrue(render(waveTable, 441) > 0);
        assertTrue(waveTable.noteOff(15, 2));
        render(waveTable, 1);
        assertEquals(0, waveTable.getPlayers());
    }

    /** an MFi audio message or a SMAF PCM audio track starts and stops a stream itself */
    @Test
    void streamOnOff() {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        NukedWaveTable waveTable = synthesizer.getWaveTable();
        YamahaVoices voices = synthesizer.getSmafVoices();

        byte[] pcm = new byte[800];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 20 < 10 ? 0x40 : 0xc0);
        voices.process(pack(StreamExclusive.wave(1, StreamExclusive.Format.UNSIGNED, 1, 8, 8000, pcm)));

        voices.process(pack(StreamExclusive.on(1, 127, 0)));
        assertTrue(render(waveTable, 441) > 0);
        voices.process(pack(StreamExclusive.volume(0, 0)));
        assertEquals(0, render(waveTable, 441));
        voices.process(pack(StreamExclusive.off(1)));
        render(waveTable, 1);
        assertEquals(0, waveTable.getPlayers());
    }

    /** a wave table note off must not reach the OPL3, and one the OPL3 started must */
    @Test
    void noteOffGoesWhereTheNoteOnWent() {
        NukedWaveTable waveTable = new NukedWaveTable(44100);
        waveTable.programChange(0, 10);
        assertFalse(waveTable.noteOn(0, 60, 100));

        // the voice arrives while the OPL3 note is held
        waveTable.setWave(0, noise(64));
        waveTable.setVoice(0, 0, 10, 0, sustainingVoice(8000, 10, 100, 0));
        assertFalse(waveTable.noteOff(0, 60));
    }

    /** an MFi stream may be number 0, stopping it must not stop a wave table voice */
    @Test
    void streamZero() {
        NukedWaveTable waveTable = new NukedWaveTable(44100);
        waveTable.setWave(0, noise(64));
        waveTable.setVoice(0, 0, 10, 0, sustainingVoice(8000, 10, 100, 0));
        waveTable.programChange(0, 10);
        waveTable.setStream(0, StreamExclusive.Format.ADPCM, 1, 4, 8000, noise(4000));

        assertTrue(waveTable.noteOn(0, 60, 100));
        waveTable.streamOn(0, 100, 0);
        assertEquals(2, waveTable.getPlayers());

        waveTable.streamOff(0);
        render(waveTable, 1);
        assertEquals(1, waveTable.getPlayers(), "the voice is still there");
    }
}
