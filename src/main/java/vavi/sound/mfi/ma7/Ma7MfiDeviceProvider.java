/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Properties;

import vavi.sound.mfi.MfiDevice;
import vavi.sound.mfi.spi.MfiDeviceProvider;
import vavi.sound.mfi.vavi.VaviMfiDeviceProvider;

import static java.lang.System.getLogger;


/**
 * {@link MfiDeviceProvider} implemented for the yamaha MA-7.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class Ma7MfiDeviceProvider extends MfiDeviceProvider {

    private static final Logger logger = getLogger(Ma7MfiDeviceProvider.class.getName());

    static {
        try {
            try (InputStream is = VaviMfiDeviceProvider.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
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
    public boolean isDeviceSupported(MfiDevice.Info info) {
        for (MfiDevice.Info mfiDeviceInfo : getDeviceInfo()) {
            if (mfiDeviceInfo.equals(info)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MfiDevice.Info[] getDeviceInfo() {
        return new MfiDevice.Info[] {
                Ma7MfiSynthesizer.info,
        };
    }

    @Override
    public MfiDevice getDevice(MfiDevice.Info info) {
        if (info == Ma7MfiSynthesizer.info) {
            Ma7MfiSynthesizer synthesizer = new Ma7MfiSynthesizer();
            return synthesizer;
        } else {
            throw new IllegalArgumentException("info is not suitable for this provider");
        }
    }
}
