/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.panasonic;

import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mfi.ucs.UcsSequencer;
import vavi.sound.mfi.vavi.sequencer.MachineDependentFunction;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;

import static vavi.sound.mfi.vavi.panasonic.PanasonicSequencer.VENDOR_PANASONIC;


/**
 * Function17. (UCS setParameters)
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-05 nsano initial version <br>
 */
public class Function17 implements MachineDependentFunction {

    @Override
    public String getId() {
        return VENDOR_PANASONIC + "." + 17;
    }

    @Override
    public void process(MachineDependentMessage message) throws InvalidMfiDataException {
        byte[] data = message.getMessage();
        if (data.length < 7) {
            throw new InvalidMfiDataException("truncated UCS message");
        }

        UcsSequencer.waveBank().setParameters(data);
    }
}
