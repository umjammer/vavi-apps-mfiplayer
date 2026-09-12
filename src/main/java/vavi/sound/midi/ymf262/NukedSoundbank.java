/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.sound.midi.Instrument;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;
import com.sun.media.sound.ModelPatch;
import com.sun.media.sound.SimpleInstrument;

import vavi.sound.midi.ymf262.NukedPlayer.opl_drum_map;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;
import vavi.sound.midi.ymf262.NukedPlayer.opl_timbre;

import static java.lang.System.getLogger;


/**
 * NukedSoundbank.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/02/25 umjammer initial version <br>
 */
public class NukedSoundbank implements Soundbank {

    private static final Logger logger = getLogger(NukedSoundbank.class.getName());

    /** */
    private final List<Instrument> instruments = new ArrayList<>();

    /** what the base of a drum map says when the note sounds nothing */
    private static final int NO_DRUM = 255;

    public NukedSoundbank() {
        opl_timbre[] instruments = NukedPlayer.getInstruments();
        for (int i = 0; i < 128; i++) {
            this.instruments.add(new NukedInstrument(0, i, false, instruments[i]));
        }
        // the drum map is indexed by the note a rhythm channel plays: "base" is the timbre
        // of that note, which several notes share, and "note" the pitch it is sounded at,
        // see NukedPlayer#midi_write - so neither of them is the program of the instrument
        opl_drum_map[] drumMaps = NukedPlayer.getDrumMaps();
        for (int note = 0; note < drumMaps.length; note++) {
            if (drumMaps[note].base != NO_DRUM)
                this.instruments.add(new NukedInstrument(128, 128 + note, true, instruments[128 + drumMaps[note].base]));
        }
    }

    @Override
    public String getName() {
        return "NukedSoundbank";
    }

    @Override
    public String getVersion() {
        return NukedSynthesizer.info.getVersion();
    }

    @Override
    public String getVendor() {
        return NukedSynthesizer.info.getVendor();
    }

    @Override
    public String getDescription() {
        return "Soundbank for NukedSynthesizer";
    }

    @Override
    public SoundbankResource[] getResources() {
        return getInstruments();
    }

    @Override
    public Instrument[] getInstruments() {
        return instruments.toArray(Instrument[]::new);
    }

    @Override
    public Instrument getInstrument(Patch patch) {
        for (Instrument instrument : instruments) {
            if (instrument.getPatch().getProgram() == patch.getProgram() &&
                    instrument.getPatch().getBank() == patch.getBank()) {
logger.log(Level.DEBUG, "request for: " + patch);
                return instrument;
            }
        }
logger.log(Level.DEBUG, "no instrument for: " + patch);
        return null;
    }

    public void setInstrument(Patch patch, Instrument newInstrument) {
        Iterator<Instrument> i = instruments.iterator();
        while (i.hasNext()) {
            Instrument instrument = i.next();
            if (instrument.getPatch().getProgram() == patch.getProgram() &&
                    instrument.getPatch().getBank() == patch.getBank()) {
logger.log(Level.TRACE, "remove already exists: " + patch);
                i.remove();
            }
        }
logger.log(Level.TRACE, "add: " + patch);
        instruments.add(newInstrument);

        // reflection an instrument data to real player
        if (newInstrument instanceof NukedInstrument) {
            opl_timbre timbre = ((NukedInstrument) newInstrument).getData();
            int program = patch.getProgram();
            int bank = patch.getBank();
            if (bank == 0) {
                NukedPlayer.getInstruments()[program] = timbre;
            } else if (bank == 128) {
                int drumNote = program >= 128 ? program - 128 : program;
                opl_drum_map[] drumMaps = NukedPlayer.getDrumMaps();
                // the map is indexed by the note, it is not a list to search through: the
                // "note" of an entry is the pitch it sounds at, so looking a note up by
                // that field finds another note's timbre, or none at all - only 35 of the
                // 128 notes have an entry whose pitch is the note itself, which is why a
                // drum voice hardly ever reached the player before
                int base = drumNote >= 0 && drumNote < drumMaps.length ? drumMaps[drumNote].base : NO_DRUM;
                if (base != NO_DRUM) {
                    NukedPlayer.getInstruments()[128 + base] = timbre;
                } else {
logger.log(Level.DEBUG, "the drum map has no note " + drumNote + ", not sounded: " + patch);
                }
            }
        }
    }

    /**
     * The timbre a VM35 (MA-3 / MA-5) FM voice becomes, which is the OPL3 registers of it
     * ({@link YamahaVoices#toOpl3Registers}) in the shape this player wants them.
     *
     * @see Vm3SoundbankReader
     * @see NukedSynthesizer
     */
    static opl_timbre toTimbre(VM35FMVoice voice) {
        int[] registers = YamahaVoices.toOpl3Registers(voice);
        int[] seed = new int[13];
        System.arraycopy(registers, 0, seed, 0, YamahaVoices.REGISTERS - 1);
        seed[10] = 0x30 | registers[10]; // the stereo bits, which a timbre carries itself
        seed[11] = 0; // note
        seed[12] = 4; // octave
        return new opl_timbre(seed);
    }

    /**
     * The timbre an OPL3 instrument of a {@link YmF262Soundbank} becomes - the bank of a
     * ".sbi", ".o3" or ".vm3" file, which is the same registers laid out for the other
     * player of this package.
     * <p>
     * Only the first two operators are taken: a four operator instrument is two OPL3
     * channels, and an {@link opl_timbre} is one.
     * </p>
     *
     * @see Vm3SoundbankReader
     */
    static opl_timbre toTimbre(Opl3Instrument instrument) {
        int[] seed = new int[13];
        for (int op = 0; op < 2; op++) {
            seed[op] = instrument.op[op].flg_mul;
            seed[2 + op] = instrument.op[op].ksl_tl;
            seed[4 + op] = instrument.op[op].ar_dr;
            seed[6 + op] = instrument.op[op].sl_rr;
            seed[8 + op] = instrument.op[op].ws;
        }
        seed[10] = 0x30 | instrument.fb_algA; // the stereo bits, which a timbre carries itself
        seed[11] = 0; // note
        seed[12] = 4; // octave
        return new opl_timbre(seed);
    }

    /** */
    public static class NukedInstrument extends SimpleInstrument {
        final opl_timbre data;
        protected NukedInstrument(int bank, int program, boolean percussion, opl_timbre instrument) {
            this(bank, program, percussion, instrument, null);
        }
        /** @param name the name the voice came with, null for the patch */
        protected NukedInstrument(int bank, int program, boolean percussion, opl_timbre instrument, String name) {
            setPatch(new ModelPatch(bank, program, percussion));
            this.name = name != null && !name.isEmpty() ? name :
                    (percussion ?  "p." : "") + bank + "." + program;
            this.data = instrument;
        }

        @Override
        public Class<opl_timbre> getDataClass() {
            return opl_timbre.class;
        }

        @Override
        public opl_timbre getData() {
            return data;
        }
    }
}
