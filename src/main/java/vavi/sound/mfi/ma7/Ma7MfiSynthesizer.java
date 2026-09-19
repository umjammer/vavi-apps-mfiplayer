/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import java.io.IOException;
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
import vavi.sound.mfi.vavi.VaviMfiSynthesizer;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;

import static java.lang.System.getLogger;


/**
 * Ma7MfiSynthesizer.
 * <p>
 * The yamaha MA-7 in pure java ({@link Ma7AudioEngine}), the rom is read out of the
 * installed {@code libM7_EmuSmw7.so}, see {@link Ma7Rom}. The adpcm comes by the exclusives of
 * the machine dependent messages, which go the way of {@link VaviMfiSynthesizer}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class Ma7MfiSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(Ma7MfiSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("Java MFi MA-7 Synthesizer",
                    "vavi",
                    "MFi yamaha MA-7 synthesizer with ADPCM",
                    "Version " + VaviMfiDeviceProvider.version) {};

    /** */
    private Ma7AudioEngine audioEngine;

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
     * An MA-7 receiver w/ ADPCM driver.
     * <p>
     * a player using {@link vavi.sound.mfi.vavi.VaviMfiSynthesizer.VaviMfiReceiver}.
     * @see MachineDependentMessage#getMidiEvents(MidiContext)
     */
    public static class Ma7MfiReceiver implements MidiDeviceReceiver {

        private boolean isOpen = true;

        /** */
        private final Ma7AudioEngine audioEngine;

        public Ma7MfiReceiver(Ma7AudioEngine audioEngine) {
            this.audioEngine = audioEngine;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!isOpen) return;

            if (message instanceof ShortMessage shortMessage) {
                audioEngine.shortMessage(shortMessage.getStatus(), shortMessage.getData1(), shortMessage.getData2());
            } else if (message instanceof SysexMessage sysexMessage) {
                if (audioEngine.exclusive(sysexMessage.getMessage())) return;
                // the adpcm is mixed into the engine's line, which has to be there by now
                audioEngine.startOutput();
                try {
                    VaviMfiSynthesizer.processSpecial(sysexMessage, this); // adpcm
                } catch (InvalidMfiDataException | RuntimeException e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
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
        if (audioEngine == null) throw new MidiUnavailableException("not opened");
        return new Ma7MfiReceiver(audioEngine);
    }

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    @Override
    public boolean isOpen() {
        return audioEngine != null;
    }

    @Override
    public void open() throws MfiUnavailableException {
        try {
            this.audioEngine = new Ma7AudioEngine();
        } catch (IOException e) {
            throw new MfiUnavailableException(e);
        }
    }

    @Override
    public void close() {
        if (audioEngine != null) {
            audioEngine.close();
            audioEngine = null;
        }
    }
}
