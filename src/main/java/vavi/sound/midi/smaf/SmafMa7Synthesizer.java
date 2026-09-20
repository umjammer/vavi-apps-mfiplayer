/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.smaf;

import javax.sound.midi.Receiver;
import javax.sound.midi.Synthesizer;

import vavi.sound.ma7.Ma7AudioEngine;
import vavi.sound.midi.ma7.Ma7Synthesizer;
import vavi.sound.mobile.AudioEngine;
import vavi.sound.smaf.ma7.Ma7SmafSynthesizer.Ma7SmafReceiver;

import static vavi.sound.midi.smaf.SmafMidiDeviceProvider.version;


/**
 * A {@link Synthesizer} that is the yamaha MA-7 playing a SMAF song, in pure java.
 * <p>
 * The sound source is the one of {@link Ma7Synthesizer} ({@link Ma7AudioEngine}, a port of the
 * MA-7 emulator of yamaha's {@code libM7_EmuSmw7.so}), and the difference to it is what the
 * exclusives of a sequence are taken for: the smaf ones of a song converted into midi by
 * vavi-sound ({@link Ma7SmafReceiver}) instead of the mfi ones.
 * <p>
 * The banks a SMAF song selects (the bank select msb 0x7c a melody, 0x7d a percussion) are the
 * banks the MA-7 driver's real time midi path knows, so the channel messages need nothing of
 * their own. The stream waves of a song are played by the adpcm engines of vavi-sound and mixed
 * into the engine's line, which wants {@code vavi.sound.mobile.AudioEngine.disabled} off (the
 * default), see {@link AudioEngine#isDisabled}: the MA-7 has no streams of its own yet.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class SmafMa7Synthesizer extends Ma7Synthesizer {

    /** the device information */
    static final Info info =
            new Info("SMAF MA-7 MIDI Synthesizer",
                     "vavi",
                     "Software synthesizer for SMAF on the yamaha MA-7",
                     "Version " + version) {};

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    /** the messages of a smaf song: the smaf exclusives of vavi and the streams a note starts */
    @Override
    protected Receiver receiver(Ma7AudioEngine engine) {
        return new Ma7SmafReceiver(engine);
    }
}
