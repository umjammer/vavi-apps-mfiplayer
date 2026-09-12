/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Soundbank;
import javax.sound.midi.spi.SoundbankReader;

import vavi.sound.yamaha.smaf.enums.Enums.VoiceType;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;

import static java.lang.System.getLogger;
import static vavi.sound.yamaha.smaf.voice.VM35Voice.VM35FMVoiceVersion.VM3Lib;


/**
 * The soundbank an MA-3 preset voice library (".vm3") is.
 * <p>
 * The FM voices of the library become the instruments of a {@link YmF262Soundbank}, the
 * OPL3 bank of this package - the one a ".sbi" or ".o3" file is read into as well - which
 * both {@link NukedSynthesizer} and {@link MatsuokaSynthesizer} take through
 * {@code loadAllInstruments}, each turning it into the timbres of its own player. So the
 * whole library is one ordinary
 * {@link javax.sound.midi.MidiSystem#getSoundbank(File)} away:
 * </p>
 * <pre>
 *  synthesizer.loadAllInstruments(MidiSystem.getSoundbank(new File("DefMA3_16.vm3")));
 * </pre>
 * <p>
 * The first voice of each patch wins, which is what gives a wave table (WT) voice a
 * timbre: a library holds the same drum note once per kit and the FM kit comes after the
 * standard one, so a note the standard kit plays a rom wave for - a wave there is no data
 * for anywhere, see {@link NukedWaveTable} - is taken from the FM kit instead, under the
 * same name. The WT voices themselves become no instrument: a preset one is always a rom
 * wave, and the adpcm engine has nothing to play.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-12 nsano initial version <br>
 * @see Vm3VoiceLib
 */
public class Vm3SoundbankReader extends SoundbankReader {

    private static final Logger logger = getLogger(Vm3SoundbankReader.class.getName());

    @Override
    public Soundbank getSoundbank(URL url) throws InvalidMidiDataException, IOException {
        try (InputStream is = new BufferedInputStream(url.openStream())) {
            return getSoundbank(is);
        }
    }

    @Override
    public Soundbank getSoundbank(File file) throws InvalidMidiDataException, IOException {
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            return getSoundbank(is);
        }
    }

    /**
     * @param stream mark should be supported, a stream which is not is buffered here and
     *               a foreign one is then left where its signature was read
     * @return null when the stream is no voice library, so that the next
     *         {@link SoundbankReader} of the spi gets it
     */
    @Override
    public Soundbank getSoundbank(InputStream stream) throws InvalidMidiDataException, IOException {
        InputStream is = stream.markSupported() ? stream : new BufferedInputStream(stream);
        is.mark(Vm3VoiceLib.SIGNATURE_LENGTH);
        byte[] header = is.readNBytes(Vm3VoiceLib.SIGNATURE_LENGTH);
        is.reset();
        if (!Vm3VoiceLib.isVoiceLib(header)) {
            return null;
        }
        return getSoundbankInternal(is);
    }

    /** the name of a soundbank this reads */
    static final String NAME = "MA-3 preset voices";

    /** */
    static Soundbank getSoundbankInternal(InputStream is) throws IOException {
        Vm3VoiceLib lib = new Vm3VoiceLib(is);
        YmF262Soundbank soundbank = new YmF262Soundbank(NAME);

        Set<Integer> done = new HashSet<>();
        int waveTableVoices = 0;
        for (Vm3VoiceLib.Entry entry : lib.getEntries()) {
            if (entry.voiceType() != VoiceType.FM) {
                if (entry.voiceType() == VoiceType.PCM) {
                    waveTableVoices++;
                }
                continue;
            }
            if (entry.image().length < Vm3VoiceLib.FM_VOICE) {
logger.log(Level.WARNING, "preset FM voice is too short: " + entry);
                continue;
            }
            if (!done.add(patchKey(entry))) {
                continue; // a later kit or bank variation of a patch which already has a voice
            }

            VM35FMVoice voice = new VM35FMVoice(entry.image(), VM3Lib);
            boolean percussion = entry.isForDrum();
            soundbank.addInstrument(
                    percussion ? 128 : 0,
                    percussion ? 128 + entry.drumNote() : entry.pc() & 0x7f,
                    entry.name(),
                    YmF262Soundbank.toInstrument(voice));
        }
logger.log(Level.DEBUG, "preset voices: " + soundbank.getInstruments().length + " FM, " +
        waveTableVoices + " wave table ones whose timbre is the FM kit's");
        return soundbank;
    }

    /** the patch of a preset voice, the same key {@code NukedSynthesizer#registerVoice} uses */
    private static int patchKey(Vm3VoiceLib.Entry entry) {
        return entry.isForDrum() ? (128 << 8) | (128 + entry.drumNote()) : entry.pc() & 0x7f;
    }
}
