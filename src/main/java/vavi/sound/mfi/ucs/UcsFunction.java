/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import javax.sound.midi.Receiver;

import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mfi.vavi.sequencer.MachineDependentFunction;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;


/**
 * A UCS machine dependent function, the same whichever vendor of a fuetrek
 * sound source sends it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
public abstract class UcsFunction implements MachineDependentFunction {

    /** fuetrek (sharp) */
    public static final int VENDOR_SHARP = 0x70;
    /** fuetrek too (P905i, P705i, ...) */
    public static final int VENDOR_PANASONIC = 0x40;

    /** 0x10 wave */
    public static final int WAVE = 0x10;
    /** 0x11 parameters */
    public static final int PARAMETERS = 0x11;
    /** 0x12 admin status */
    public static final int ADMIN_STATUS = 0x12;

    private final int vendor;
    private final int function;

    protected UcsFunction(int vendor, int function) {
        this.vendor = vendor;
        this.function = function;
    }

    @Override
    public String getId() {
        return vendor + "." + function;
    }

    @Override
    public void process(MachineDependentMessage message, Receiver receiver) throws InvalidMfiDataException {
        byte[] data = message.getMessage();
        if (data.length < 7) {
            throw new InvalidMfiDataException("truncated UCS message");
        }

        UcsSequencer.UcsWaveBank waveBank = UcsSequencer.waveBank();
        switch (function) {
        case WAVE -> waveBank.setWave(data);
        case PARAMETERS -> waveBank.setParameters(data);
        case ADMIN_STATUS -> waveBank.setAdminStatus(data);
        default -> throw new IllegalStateException("not a UCS function: " + function);
        }
    }
}
