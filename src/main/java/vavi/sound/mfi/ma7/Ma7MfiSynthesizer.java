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

import vavi.sound.ma7.Ma7AudioEngine;
import vavi.sound.ma7.Ma7Rom;
import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mfi.MfiUnavailableException;
import vavi.sound.mfi.Synthesizer;
import vavi.sound.mfi.vavi.MidiContext;
import vavi.sound.mfi.vavi.VaviMfiDeviceProvider;
import vavi.sound.mfi.vavi.VaviMfiSynthesizer;
import vavi.sound.mfi.vavi.sequencer.MfiValueExclusive;
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
     * The channel messages go to the sound source as they come. The mfi values vavi sends along
     * with the midi it converts mfi into ({@link MfiValueExclusive}) are taken as the library's
     * own mfi converter ({@code YAMAHA::MaMfiCnv}) takes them:
     * <ul>
     * <li>the bank ... {@link Ma7AudioEngine#bankChange}, the program change following is of it</li>
     * <li>the master volume ... the song's, the universal master volume following is it and goes
     *     to the sound source, any other is the listener's and is a gain after it</li>
     * </ul>
     * a player using {@link vavi.sound.mfi.vavi.VaviMfiSynthesizer.VaviMfiReceiver}.
     * @see MachineDependentMessage#getMidiEvents(MidiContext)
     */
    public static class Ma7MfiReceiver implements MidiDeviceReceiver {

        private boolean isOpen = true;

        /** the next universal master volume is the song's, not the listener's */
        private boolean songVolume;

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
                byte[] data = sysexMessage.getMessage();
                // the mfi values: f0 45 04 sub ... f7
                switch (MfiValueExclusive.sub(data)) {
                case MfiValueExclusive.BANK -> {
                    if (data.length >= 7) audioEngine.bankChange(data[4] & 0x0f, data[5] & 0x3f);
                    return;
                }
                case MfiValueExclusive.MASTER_VOLUME -> {
                    songVolume = true;
                    return;
                }
                case -1 -> {}
                default -> {
                    // the rest the sound source has nothing of
                    return;
                }
                }
                // the master volume of the song goes to the sound source, the listener's is a gain after it
                if (songVolume && Ma7AudioEngine.isMasterVolume(data)) {
                    songVolume = false;
                    audioEngine.sourceExclusive(data);
                    return;
                }
                if (audioEngine.exclusive(data)) return;
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
