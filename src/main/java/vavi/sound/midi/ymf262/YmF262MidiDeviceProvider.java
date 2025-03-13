/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import javax.sound.midi.MidiDevice;
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

    /** */
    public final static int MANUFACTURER_ID = 0x43;

    /** */
    private static final MidiDevice.Info[] infos = new MidiDevice.Info[] {
            MatsuokaSynthesizer.info,
            NukedSynthesizer.info
    };

    @Override
    public MidiDevice.Info[] getDeviceInfo() {
        return infos;
    }

    /** */
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
