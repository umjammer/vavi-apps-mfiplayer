/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.Instrument;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;
import com.sun.media.sound.ModelPatch;
import com.sun.media.sound.SimpleInstrument;

import vavi.sound.midi.ymf262.NukedPlayer.opl_drum_map;
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

    public NukedSoundbank() {
        opl_timbre[] instruments = NukedPlayer.getInstruments();
        for (int i = 0; i < 128; i++) {
            this.instruments.add(new NukedInstrument(0, i, false, instruments[i]));
        }
        opl_drum_map[] drumMaps = NukedPlayer.getDrumMaps();
        for (opl_drum_map drumMap : drumMaps) {
            if (drumMap.base != 255)
                this.instruments.add(new NukedInstrument(128, 128 + drumMap.note, true, instruments[128 + drumMap.note]));
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

    /** */
    public static class NukedInstrument extends SimpleInstrument {
        final opl_timbre data;
        protected NukedInstrument(int bank, int program, boolean percussion, opl_timbre instrument) {
            setPatch(new ModelPatch(bank, program, percussion));
            this.name = (percussion ?  "p." : "") + bank + "." + program;
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
