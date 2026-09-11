/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDeviceReceiver;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SysexMessage;

import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mfi.MfiUnavailableException;
import vavi.sound.mfi.Synthesizer;
import vavi.sound.mfi.vavi.MidiContext;
import vavi.sound.mfi.vavi.VaviMfiDeviceProvider;
import vavi.sound.mfi.vavi.VaviSynthesizer;
import vavi.sound.mfi.vavi.sequencer.MfiMessageStore;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;

import static java.lang.System.getLogger;


/**
 * UcsSynthesizer.
 * <p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-03-13 nsano initial version <br>
 */
public class UcsSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(UcsSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("Java MFi UCS Synthesizer",
                    "vavi",
                    "MFi UCS synthesizer with ADPCM",
                    "Version " + VaviMfiDeviceProvider.version) {};

    /** */
    private UcsAudioEngine ucsAudioEngine;

    @Override
    public MidiChannel[] getChannels() {
        return new MidiChannel[0];
    }

    @Override
    public Soundbank getDefaultSoundbank() {
        return null;
    }

    @Override
    public Instrument[] getAvailableInstruments() {
        return new Instrument[0];
    }

    @Override
    public boolean loadAllInstruments(Soundbank soundbank) {
        return false;
    }

    @Override
    public void unloadAllInstruments(Soundbank soundbank) {
    }

    /**
     * An UCS Receiver w/ ADPCM driver.
     * <p>
     * a player using {@link MfiMessageStore}.
     * @see MachineDependentMessage#getMidiEvents(MidiContext)
     */
    public static class UcsReceiver implements MidiDeviceReceiver {
        boolean isOpen;

        /** */
        private final UcsAudioEngine ucsAudioEngine;

        public UcsReceiver(UcsAudioEngine ucsAudioEngine) {
            this.ucsAudioEngine = ucsAudioEngine;
            isOpen = true;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!isOpen) return;

            if (message instanceof ShortMessage shortMessage) {
                if (ucsAudioEngine.isUcsChannel(shortMessage.getChannel())) {
                    if (shortMessage.getCommand() == ShortMessage.NOTE_ON) {
                        ucsAudioEngine.noteOn(shortMessage.getChannel(), shortMessage.getData1(), shortMessage.getData2());
                    } else if (shortMessage.getCommand() == ShortMessage.NOTE_OFF) {
                        ucsAudioEngine.noteOff(shortMessage.getChannel(), shortMessage.getData1());
                    }
                }
            } else if (message instanceof SysexMessage sysexMessage) {
                try {
                    VaviSynthesizer.processSpecial(sysexMessage, this);
                } catch (InvalidMfiDataException e) {
                    logger.log(Level.ERROR, e.getCause().getMessage(), e.getCause());
} catch (RuntimeException e) {
 logger.log(Level.ERROR, e.getMessage(), e);
} catch (Error e) {
 logger.log(Level.ERROR, e.getMessage(), e);
 throw e;
}
            }
        }

        @Override
        public void close() {
            isOpen = false;
        }

        @Override
        public MidiDevice getMidiDevice() {
            return null;
        }
    }

    @Override
    public Receiver getReceiver() throws MidiUnavailableException {
        return new UcsReceiver(ucsAudioEngine);
    }

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public void open() throws MfiUnavailableException {
        this.ucsAudioEngine = new UcsAudioEngine();
    }

    @Override
    public void close() {
        ucsAudioEngine.close();
    }
}
