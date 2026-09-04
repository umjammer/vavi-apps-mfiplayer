/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.faith;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.spi.MidiDeviceProvider;

import static java.lang.System.getLogger;


/**
 * FaithMidiDeviceProvider.
 * <p>
 * The one device is {@link FaithSynthesizer}, and it is offered whether or not
 * {@code rt_synth_4.dll} is on this machine: a provider that hid it would have
 * {@code MidiSystem} say there is no such device where what is true is that the dll has not been
 * found, and the second of those is worth saying. It is said by {@link FaithSynthesizer#open},
 * which is where the dll is first wanted.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
public class FaithMidiDeviceProvider extends MidiDeviceProvider {

    private static final Logger logger = getLogger(FaithMidiDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = FaithMidiDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
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

    /** fuetrek, whose voice engine this is */
    public static final int MANUFACTURER_ID = 0x50;

    @Override
    public MidiDevice.Info[] getDeviceInfo() {
        return new MidiDevice.Info[] {
                FaithSynthesizer.info
        };
    }

    @Override
    public MidiDevice getDevice(MidiDevice.Info info) throws IllegalArgumentException {
        if (info == FaithSynthesizer.info) {
logger.log(Level.DEBUG, "info: " + info);
            return new FaithSynthesizer();
        } else {
logger.log(Level.DEBUG, "not mine: " + info);
            throw new IllegalArgumentException(String.valueOf(info));
        }
    }
}
