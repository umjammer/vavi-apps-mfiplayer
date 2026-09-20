/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.smaf;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.spi.MidiDeviceProvider;

import static java.lang.System.getLogger;


/**
 * SmafMidiDeviceProvider.
 * <p>
 * The one device is {@link SmafMa7Synthesizer}, and it is offered whether or not
 * {@code libM7_EmuSmw7.so}, where the rom of the MA-7 it plays on is, is on this machine, as
 * {@link vavi.sound.midi.ma7.Ma7MidiDeviceProvider} offers the mfi one.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class SmafMidiDeviceProvider extends MidiDeviceProvider {

    private static final Logger logger = getLogger(SmafMidiDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = SmafMidiDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
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

    @Override
    public MidiDevice.Info[] getDeviceInfo() {
        return new MidiDevice.Info[] {
                SmafMa7Synthesizer.info
        };
    }

    @Override
    public MidiDevice getDevice(MidiDevice.Info info) throws IllegalArgumentException {
        if (info == SmafMa7Synthesizer.info) {
logger.log(Level.DEBUG, "info: " + info);
            return new SmafMa7Synthesizer();
        } else {
logger.log(Level.DEBUG, "not mine: " + info);
            throw new IllegalArgumentException(String.valueOf(info));
        }
    }
}
