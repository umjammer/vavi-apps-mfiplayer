/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.fuetrek;

import javax.sound.midi.Receiver;

import vavi.sound.fuetrek.UcsWaveBank;
import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mfi.vavi.sequencer.MachineDependentFunction;


/**
 * A UCS machine dependent function, the same whichever vendor of a fuetrek
 * sound source sends it.
 * <p>
 * vavi-sound has classes of its own for the same messages
 * ({@code vavi.sound.mfi.vavi.sharp.Function16} ~ {@code 18}, {@code panasonic} too), which
 * only read them. So these are not named after them - a class of the same name in two jars is
 * whichever the class path has first - and they are of a higher {@link #getPriority() priority}:
 * they are what puts the waves into the sound source ({@link UcsWaveBank}).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 *          0.01 2026-09-23 nsano the providers, out of vavi-sound's packages <br>
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

    /** over vavi-sound's, which only reads the messages */
    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public void process(byte[] data, Receiver receiver) throws InvalidMfiDataException {
        if (data.length < 7) {
            throw new InvalidMfiDataException("truncated UCS message");
        }

        switch (function) {
        case WAVE -> UcsSequencer.setWave(data);
        case PARAMETERS -> UcsSequencer.setParameters(data);
        case ADMIN_STATUS -> UcsSequencer.setAdminStatus(data);
        default -> throw new IllegalStateException("not a UCS function: " + function);
        }
    }

    /** 0x70 0x10, the waves */
    public static class SharpWave extends UcsFunction {
        public SharpWave() {
            super(VENDOR_SHARP, WAVE);
        }
    }

    /** 0x70 0x11, the voice parameters */
    public static class SharpParameters extends UcsFunction {
        public SharpParameters() {
            super(VENDOR_SHARP, PARAMETERS);
        }
    }

    /** 0x70 0x12, the tone a wave is */
    public static class SharpAdminStatus extends UcsFunction {
        public SharpAdminStatus() {
            super(VENDOR_SHARP, ADMIN_STATUS);
        }
    }

    /** 0x40 0x10, the waves */
    public static class PanasonicWave extends UcsFunction {
        public PanasonicWave() {
            super(VENDOR_PANASONIC, WAVE);
        }
    }

    /** 0x40 0x11, the voice parameters */
    public static class PanasonicParameters extends UcsFunction {
        public PanasonicParameters() {
            super(VENDOR_PANASONIC, PARAMETERS);
        }
    }

    /** 0x40 0x12, the tone a wave is */
    public static class PanasonicAdminStatus extends UcsFunction {
        public PanasonicAdminStatus() {
            super(VENDOR_PANASONIC, ADMIN_STATUS);
        }
    }
}
