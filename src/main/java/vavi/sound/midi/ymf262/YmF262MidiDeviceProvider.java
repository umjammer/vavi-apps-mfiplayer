/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.spi.MidiDeviceProvider;

import static java.lang.System.getLogger;


/**
 * YmF262MidiDeviceProvider.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 250225 nsano initial version <br>
 */
public class YmF262MidiDeviceProvider extends MidiDeviceProvider {

    private static final Logger logger = getLogger(YmF262MidiDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = YmF262MidiDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    version = props.getProperty("version", "undefined in pom.properties");
                } else {
                    version = System.getProperty("vavi.test.version", "undefined");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String version;

    /** YAMAHA */
    public final static int MANUFACTURER_ID = 0x43;

    /**
     * The system property naming a soundbank file for a synthesizer of this package to
     * play, which is read through the {@link javax.sound.midi.spi.SoundbankReader} spi.
     * <p>
     * Without it each plays the OPL3 (YMF262) bank it comes with, which is what they were
     * made for. With it the instruments of the file replace the patches they are for - an
     * MA-3 preset voice library (".vm3") is one such file, and the one this has a reader
     * for, see {@link Vm3SoundbankReader}. A voice an MFi or SMAF file sends still
     * replaces its patch either way, that is the file talking and not the bank, see
     * {@link YamahaVoices}. An empty value is "nothing named", which is how a command line
     * says the OPL3 bank over a properties file naming one.
     * </p>
     */
    public static final String SOUNDBANK_KEY = "vavi.sound.midi.ymf262.soundbank";

    /**
     * Loads the soundbank {@link #SOUNDBANK_KEY} names into a synthesizer of this package,
     * when it names one. The synthesizer must be open enough to sound an instrument.
     */
    static void loadSoundbank(Synthesizer synthesizer) {
        String path = System.getProperty(SOUNDBANK_KEY);
        if (path == null || path.isBlank()) {
logger.log(Level.DEBUG, "bank: the OPL3 (YMF262) one");
            return;
        }
        try {
            synthesizer.loadAllInstruments(MidiSystem.getSoundbank(new File(path)));
        } catch (InvalidMidiDataException | IOException | RuntimeException e) {
logger.log(Level.WARNING, "cannot read the soundbank " + path + ", the bank stays the OPL3 one: " + e);
        }
    }

    @Override
    public MidiDevice.Info[] getDeviceInfo() {
        return new MidiDevice.Info[] {
                MatsuokaSynthesizer.info,
                NukedSynthesizer.info
        };
    }

    @Override
    public MidiDevice getDevice(MidiDevice.Info info)
        throws IllegalArgumentException {

        if (info == MatsuokaSynthesizer.info) {
logger.log(Level.DEBUG, "★1 info: " + info);
            MatsuokaSynthesizer synthesizer = new MatsuokaSynthesizer();
            return synthesizer;
        } else if (info == NukedSynthesizer.info) {
logger.log(Level.DEBUG, "★1 info: " + info);
            NukedSynthesizer synthesizer = new NukedSynthesizer();
            return synthesizer;
        } else {
logger.log(Level.DEBUG, "★1 here: " + info);
            throw new IllegalArgumentException();
        }
    }
}
