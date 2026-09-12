/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sound.midi.Instrument;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.sound.midi.ymf262.NukedSoundbank.NukedInstrument;
import vavi.sound.midi.ymf262.YmF262Soundbank.YmF262Instrument;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static vavi.sound.midi.ymf262.Vm3VoiceLibTest.SNARE_M;
import static vavi.sound.midi.ymf262.Vm3VoiceLibTest.library;


/**
 * Tests the soundbank an MA-3 preset voice library is, and the bank the synthesizer plays.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-12 nsano initial version <br>
 * @see Vm3SoundbankReader
 */
@PropsEntity(url = "file:local.properties")
class Vm3SoundbankReaderTest {

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

    /** the TL of the first operator of a voice, what tells the voices of the library apart */
    private static int tl(Instrument instrument) {
        return switch (instrument) {
            case YmF262Instrument i -> ((Opl3Instrument) i.getData()).op[0].ksl_tl;
            case NukedInstrument i -> i.getData().tl[0];
            default -> throw new IllegalArgumentException(instrument.toString());
        };
    }

    /** OPL3's CNT bit of a voice, 0: the first operator modulates the second */
    private static int connection(Instrument instrument) {
        return switch (instrument) {
            case YmF262Instrument i -> ((Opl3Instrument) i.getData()).fb_algA & 0x01;
            case NukedInstrument i -> i.getData().fb & 0x01;
            default -> throw new IllegalArgumentException(instrument.toString());
        };
    }

    /**
     * The FM voices become instruments and a drum note the standard kit plays a rom wave
     * for gets the timbre of the FM kit - the whole point of reading a preset library.
     */
    @Test
    void soundbank() throws Exception {
        Soundbank soundbank = new Vm3SoundbankReader().getSoundbank(new ByteArrayInputStream(library()));

        assertNotNull(soundbank);
        assertEquals(Vm3SoundbankReader.NAME, soundbank.getName());
        // the WT voice becomes none, the melody variation of a patch which has one neither
        assertEquals(3, soundbank.getInstruments().length);

        Instrument melody = soundbank.getInstrument(new Patch(0, 5));
        assertEquals("GrandPno", melody.getName());
        assertEquals(11, tl(melody), "the first voice of a patch wins, not the LSB 10 variation");

        Instrument drum = soundbank.getInstrument(new Patch(128, 128 + SNARE_M));
        assertEquals("SnareM", drum.getName());
        assertEquals(42, tl(drum), "the timbre of a rom wave note is the FM kit's");

        // A5 (FB(1)->2 + FB(3)->4) modulates, A1 (FB(1) + 2) does not
        assertEquals(0, connection(drum));
        assertEquals(1, connection(soundbank.getInstrument(new Patch(0, 6))));
    }

    /** a stream of another format is handed back to the next reader of the spi, not thrown on */
    @Test
    void notAVoiceLib() throws Exception {
        byte[] data = library();
        data[0] = 'X';
        assertNull(new Vm3SoundbankReader().getSoundbank(new ByteArrayInputStream(data)));
    }

    /** the library is a soundbank of the spi, that is what {@code MidiSystem} answers with */
    @Test
    void spi() throws Exception {
        Path path = Files.createTempFile("vavi", ".vm3");
        try {
            Files.write(path, library());
            Soundbank soundbank = MidiSystem.getSoundbank(path.toFile());
            assertEquals(Vm3SoundbankReader.NAME, soundbank.getName());
            assertEquals(3, soundbank.getInstruments().length);
        } finally {
            Files.delete(path);
        }
    }

    /** loading a bank is what hands the timbres to the player of a synthesizer */
    @Test
    void load() throws Exception {
        NukedSynthesizer synthesizer = new NukedSynthesizer();
        Soundbank soundbank = new Vm3SoundbankReader().getSoundbank(new ByteArrayInputStream(library()));

        assertTrue(synthesizer.loadAllInstruments(soundbank));

        assertEquals(11, NukedPlayer.getInstruments()[5].tl[0]);
        assertEquals(42, NukedPlayer.getInstruments()[128 + SNARE_M].tl[0]);
        // and the bank of the synthesizer is the one which answers for the patch now
        assertEquals(11, tl(synthesizer.getDefaultSoundbank().getInstrument(new Patch(0, 5))));
    }

    /**
     * The library is the OPL3 bank of this package, which is the bank of both players: one
     * keeps it as {@code opl_timbre} and the other as {@code Opl3Instrument}, and the file
     * is neither.
     */
    @Test
    void bothPlayers() throws Exception {
        Soundbank soundbank = new Vm3SoundbankReader().getSoundbank(new ByteArrayInputStream(library()));

        assertTrue(new NukedSynthesizer().isSoundbankSupported(soundbank));
        assertTrue(new MatsuokaSynthesizer().isSoundbankSupported(soundbank));

        // what MatsuokaSynthesizer#loadInstrument hands its player, which needs no line
        MatsuokaPlayer player = new MatsuokaPlayer();
        Instrument melody = soundbank.getInstrument(new Patch(0, 5));
        player.setInstrument(5, (Opl3Instrument) melody.getData());
        assertEquals(11, player.opl3_ins[5].op[0].ksl_tl);

        Instrument drum = soundbank.getInstrument(new Patch(128, 128 + SNARE_M));
        player.setDrum(SNARE_M, (Opl3Instrument) drum.getData());
        assertEquals(42, player.opl3_drum[SNARE_M].op[0].ksl_tl);
    }

    /** "/usr/local/src/mmftool/DefMA3_16.vm3": 128 melody programs and the 79 notes of a kit */
    @Test
    void defMA3() throws Exception {
        assumeTrue(Files.exists(Path.of(vm3)), vm3 + " is not here");

        Soundbank soundbank = MidiSystem.getSoundbank(new java.io.File(vm3));
        assertEquals(Vm3SoundbankReader.NAME, soundbank.getName());
        assertEquals(128 + 79, soundbank.getInstruments().length);

        NukedSynthesizer synthesizer = new NukedSynthesizer();
        assertTrue(synthesizer.loadAllInstruments(soundbank));
        // the rom wave notes of the kit have a timbre too, the FM kit's
        assertEquals("SnareM", soundbank.getInstrument(new Patch(128, 128 + SNARE_M)).getName());
        assertEquals("GrandPno", soundbank.getInstrument(new Patch(0, 0)).getName());
    }
}
