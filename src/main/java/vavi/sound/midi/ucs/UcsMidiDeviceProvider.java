/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ucs;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.spi.MidiDeviceProvider;

import static java.lang.System.getLogger;


/**
 * UcsMidiDeviceProvider.
 * <p>
 * The one device is {@link UcsSynthesizer}, and it is offered whether or not
 * {@code rt_synth_4.dll}, where its rom is, is on this machine: a provider that hid it would have
 * {@code MidiSystem} say there is no such device where what is true is that the dll has not been
 * found, and the second of those is worth saying. It is said by {@link UcsSynthesizer#open()},
 * which is where the dll is first wanted.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public class UcsMidiDeviceProvider extends MidiDeviceProvider {

    private static final Logger logger = getLogger(UcsMidiDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = UcsMidiDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
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
                UcsSynthesizer.info
        };
    }

    @Override
    public MidiDevice getDevice(MidiDevice.Info info) throws IllegalArgumentException {
        if (info == UcsSynthesizer.info) {
logger.log(Level.DEBUG, "info: " + info);
            return new UcsSynthesizer();
        } else {
logger.log(Level.DEBUG, "not mine: " + info);
            throw new IllegalArgumentException(String.valueOf(info));
        }
    }
}
