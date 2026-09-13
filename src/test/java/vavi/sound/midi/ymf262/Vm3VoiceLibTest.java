/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vavi.sound.yamaha.smaf.enums.Enums.VoiceType;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * Tests the MA-3 preset voice library of a ".vm3" file.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-12 nsano initial version <br>
 * @see Vm3VoiceLib
 */
@PropsEntity(url = "file:local.properties")
class Vm3VoiceLibTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    /** an MA-3 preset voice library, which is not ours to bundle */
    @Property
    String vm3 = "/usr/local/src/mmftool/DefMA3_16.vm3";

    @BeforeEach
    void setup() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    /** the drum note the standard MA-3 kit plays a rom wave for, "Snare M" */
    static final int SNARE_M = 38;

    /**
     * A 31 byte VM35 FM voice image, {@code alg} and the TL of its first operator being
     * everything the tests here read back out of it.
     */
    static byte[] fmVoice(int alg, int tl) {
        byte[] image = new byte[Vm3VoiceLib.FM_VOICE];
        image[2] = (byte) alg;          // LFO, PE, ALG
        image[3 + 3] = (byte) (tl << 2);// operator 0: TL, KSL
        return image;
    }

    /** a 16 byte VM35 PCM voice image playing rom wave {@code waveId} */
    static byte[] pcmVoice(int waveId) {
        byte[] image = new byte[Vm3VoiceLib.PCM_VOICE];
        image[0] = (byte) (16000 >> 8);          // Fs(MSB)
        image[1] = (byte) (16000 & 0xff);       // Fs(LSB)
        image[15] = (byte) (0x80 | waveId);    // RM = 1, WaveID
        return image;
    }

    /** &lt;no&gt; &lt;size&gt; mm ll pc dn vt &lt;name&gt; &lt;voice&gt; */
    static byte[] record(int number, int bankMSB, int bankLSB, int pc, int drumNote,
                         VoiceType voiceType, String name, byte[] image) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(number >> 8);
        b.write(number & 0xff);
        b.write(5 + 16 + image.length);
        b.write(bankMSB);
        b.write(bankLSB);
        b.write(pc);
        b.write(drumNote);
        b.write(voiceType.ordinal());
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        int length = Math.min(n.length, 16);
        b.write(n, 0, length);
        b.write(new byte[16 - length], 0, 16 - length);
        b.writeBytes(image);
        return b.toByteArray();
    }

    /** "FMM3" &lt;size&gt; &lt;records&gt; */
    static byte[] voiceLib(byte[]... records) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] record : records) {
            body.writeBytes(record);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("FMM3".getBytes(StandardCharsets.US_ASCII));
        int size = body.size();
        b.write(size >> 24); b.write(size >> 16); b.write(size >> 8); b.write(size);
        b.writeBytes(body.toByteArray());
        return b.toByteArray();
    }

    /** the shape of a library: a melody bank and its variation, a drum kit and the FM kit */
    static byte[] library() {
        return voiceLib(
                record(0, Vm3VoiceLib.MELODY_BANK, 0, 5, 0, VoiceType.FM, "GrandPno", fmVoice(0, 11)),
                record(1, Vm3VoiceLib.MELODY_BANK, 0, 6, 0, VoiceType.FM, "E.Piano1", fmVoice(1, 12)),
                // the same program again, the bank LSB variation every library repeats
                record(2, Vm3VoiceLib.MELODY_BANK, 10, 5, 0, VoiceType.FM, "GrandPno", fmVoice(0, 13)),
                // the standard kit, which plays this note with a rom wave
                record(3, Vm3VoiceLib.DRUM_BANK, 0, 0, SNARE_M, VoiceType.PCM, "Snare M", pcmVoice(1)),
                // the FM kit, which is the timbre of that note
                record(4, Vm3VoiceLib.DRUM_BANK, 0, 1, SNARE_M, VoiceType.FM, "SnareM", fmVoice(5, 42)));
    }

    @Test
    void entries() throws Exception {
        Vm3VoiceLib lib = new Vm3VoiceLib(new ByteArrayInputStream(library()));
        List<Vm3VoiceLib.Entry> entries = lib.getEntries();

        assertEquals(5, entries.size());

        Vm3VoiceLib.Entry melody = entries.getFirst();
        assertEquals(Vm3VoiceLib.MELODY_BANK, melody.bankMSB());
        assertEquals(0, melody.bankLSB());
        assertEquals(5, melody.pc());
        assertEquals(VoiceType.FM, melody.voiceType());
        assertEquals("GrandPno", melody.name());
        assertEquals(Vm3VoiceLib.FM_VOICE, melody.image().length);
        assertFalse(melody.isForDrum());

        Vm3VoiceLib.Entry waveTable = entries.get(3);
        assertEquals(Vm3VoiceLib.DRUM_BANK, waveTable.bankMSB());
        assertEquals(SNARE_M, waveTable.drumNote());
        assertEquals(VoiceType.PCM, waveTable.voiceType());
        assertEquals("Snare M", waveTable.name());
        assertEquals(Vm3VoiceLib.PCM_VOICE, waveTable.image().length);
        assertTrue(waveTable.isForDrum());
    }

    @Test
    void notAVoiceLib() {
        byte[] data = library();
        data[0] = 'X';
        assertFalse(Vm3VoiceLib.isVoiceLib(data));
        assertThrows(IllegalArgumentException.class,
                () -> new Vm3VoiceLib(new ByteArrayInputStream(data)));
    }

    /**
     * What "/usr/local/src/mmftool/DefMA3_16.vm3" holds: a melody bank and its LSB 10
     * variation, and three drum kits of 79 notes, 21 of which the standard kit plays a rom
     * wave for and the kit next to it an FM voice.
     */
    @Test
    void defMA3() throws Exception {
        assumeTrue(Files.exists(Path.of(vm3)), vm3 + " is not here");

        Vm3VoiceLib lib;
        try (InputStream is = Files.newInputStream(Path.of(vm3))) {
            lib = new Vm3VoiceLib(is);
        }
        List<Vm3VoiceLib.Entry> entries = lib.getEntries();
        assertEquals(493, entries.size());
        assertEquals(42, entries.stream().filter(e -> e.voiceType() == VoiceType.PCM).count());
        assertEquals(256, entries.stream().filter(e -> e.bankMSB() == Vm3VoiceLib.MELODY_BANK).count());
        assertEquals(237, entries.stream().filter(e -> e.bankMSB() == Vm3VoiceLib.DRUM_BANK).count());

        // the standard kit plays "Snare M" with a rom wave, the FM kit next to it has the timbre
        assertEquals(VoiceType.PCM, kit(entries, 0, SNARE_M).voiceType());
        assertEquals(VoiceType.FM, kit(entries, 1, SNARE_M).voiceType());
        // and that is true of all 21 rom wave notes of the kit
        entries.stream()
                .filter(e -> e.bankMSB() == Vm3VoiceLib.DRUM_BANK && e.pc() == 0 && e.voiceType() == VoiceType.PCM)
                .forEach(e -> assertEquals(VoiceType.FM, kit(entries, 1, e.drumNote()).voiceType(),
                        "note " + e.drumNote() + " [" + e.name() + "]"));
    }

    /** the voice of a note of one drum kit of a library */
    private static Vm3VoiceLib.Entry kit(List<Vm3VoiceLib.Entry> entries, int pc, int note) {
        return entries.stream()
                .filter(e -> e.bankMSB() == Vm3VoiceLib.DRUM_BANK && e.pc() == pc && e.drumNote() == note)
                .findFirst().orElseThrow();
    }
}
