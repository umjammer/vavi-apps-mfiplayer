/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma7;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;

import vavi.sound.smaf.SmafDevice;
import vavi.sound.smaf.spi.SmafDeviceProvider;

import static java.lang.System.getLogger;


/**
 * {@link SmafDeviceProvider} implemented for the yamaha MA-7.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class Ma7SmafDeviceProvider extends SmafDeviceProvider {

    private static final Logger logger = getLogger(Ma7SmafDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = Ma7SmafDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    version = props.getProperty("version", "undefined in pom.properties");
                } else {
                    version = System.getProperty("vavi.test.version", "undefined");
                }
            }
        } catch (Exception e) {
logger.log(Level.ERROR, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    /** */
    public static final String version;

    @Override
    public boolean isDeviceSupported(SmafDevice.Info info) {
        for (SmafDevice.Info smafDeviceInfo : getDeviceInfo()) {
            if (smafDeviceInfo.equals(info)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public SmafDevice.Info[] getDeviceInfo() {
        return new SmafDevice.Info[] {
                Ma7SmafSynthesizer.info,
        };
    }

    @Override
    public SmafDevice getDevice(SmafDevice.Info info) {
        if (info == Ma7SmafSynthesizer.info) {
            Ma7SmafSynthesizer synthesizer = new Ma7SmafSynthesizer();
            return synthesizer;
        } else {
            throw new IllegalArgumentException("info is not suitable for this provider");
        }
    }
}
