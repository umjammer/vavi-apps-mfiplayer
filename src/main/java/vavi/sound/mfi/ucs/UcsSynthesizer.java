/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

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
import vavi.sound.mfi.vavi.VaviSynthesizer;
import vavi.sound.mfi.vavi.sequencer.FuetrekMfiExclusive;
import vavi.sound.mfi.vavi.sequencer.MfiMessageStore;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;

import static java.lang.System.getLogger;


/**
 * UcsSynthesizer.
 * <p>
 * The fuetrek sound source in pure java ({@link UcsAudioEngine}), the preset
 * tones are read out of the installed {@code rt_synth_4.dll}, see {@link FuetrekRom}.
 * The UCS waves in a file and the adpcm come by the exclusives of the
 * machine dependent messages, which go the way of {@link VaviSynthesizer}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-03-13 nsano initial version <br>
 *          0.01 2026-09-15 nsano pure java fuetrek sound source <br>
 */
public class UcsSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(UcsSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("Java MFi UCS Synthesizer",
                    "vavi",
                    "MFi fuetrek synthesizer with UCS and ADPCM",
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

        private boolean isOpen = true;

        /** the next universal master volume is the song's, already taken */
        private boolean songVolume;

        /** */
        private final UcsAudioEngine ucsAudioEngine;

        public UcsReceiver(UcsAudioEngine ucsAudioEngine) {
            this.ucsAudioEngine = ucsAudioEngine;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!isOpen) return;

            if (message instanceof ShortMessage shortMessage) {
                int channel = shortMessage.getChannel();
                int data1 = shortMessage.getData1();
                int data2 = shortMessage.getData2();
                switch (shortMessage.getCommand()) {
                    case ShortMessage.NOTE_ON -> ucsAudioEngine.noteOn(channel, data1, data2);
                    case ShortMessage.NOTE_OFF -> ucsAudioEngine.noteOff(channel, data1);
                    case ShortMessage.PROGRAM_CHANGE -> ucsAudioEngine.programChange(channel, data1);
                    case ShortMessage.CONTROL_CHANGE -> ucsAudioEngine.controlChange(channel, data1, data2);
                    case ShortMessage.PITCH_BEND -> ucsAudioEngine.pitchBend(channel, data1 | (data2 << 7));
                    case ShortMessage.CHANNEL_PRESSURE -> ucsAudioEngine.channelPressure(channel, data1);
                    default -> {}
                }
            } else if (message instanceof SysexMessage sysexMessage) {
                byte[] data = sysexMessage.getMessage();
                // the mfi values: f0 45 04 sub ... f7
                switch (FuetrekMfiExclusive.sub(data)) {
                    case FuetrekMfiExclusive.BANK -> {
                        if (data.length >= 7) ucsAudioEngine.bankChange(data[4] & 0x0f, data[5]);
                        return;
                    }
                    case FuetrekMfiExclusive.MASTER_VOLUME -> {
                        // the universal one following is the same
                        if (data.length >= 6) ucsAudioEngine.masterVolume(data[4] & 0x7f);
                        songVolume = true;
                        return;
                    }
                    case FuetrekMfiExclusive.PITCH_BEND_FINE -> {
                        if (data.length >= 7) ucsAudioEngine.pitchBendFine(data[4] & 0x0f, data[5] & 0x3f);
                        return;
                    }
                    case FuetrekMfiExclusive.PITCH_BEND_RANGE -> {
                        if (data.length >= 7) ucsAudioEngine.mfiPitchBendRange(data[4] & 0x0f, data[5] & 0x3f);
                        return;
                    }
                    case -1 -> {}
                    default -> {
                        return;
                    }
                }
                // universal device control: f0 7f 7f 04 nn ll mm f7, as the dll takes them
                if (data.length >= 7 && (data[0] & 0xff) == 0xf0 && data[1] == 0x7f && data[3] == 0x04) {
                    int value = (data[5] & 0x7f) | ((data[6] & 0x7f) << 7);
                    switch (data[4]) {
                        case 0x01 -> {
                            // master volume, the listener's unless marked above
                            if (songVolume) {
                                songVolume = false;
                            } else {
                                ucsAudioEngine.hostVolume(value / 16383d);
                            }
                        }
                        case 0x02 -> ucsAudioEngine.masterBalance(data[6] & 0x7f);
                        case 0x03 -> ucsAudioEngine.masterFineTuning(value);
                        case 0x04 -> ucsAudioEngine.masterCoarseTuning(data[6] & 0x7f);
                        default -> {}
                    }
                    return;
                }
                // gm system on: f0 7e 7f 09 01 f7
                if (data.length >= 5 && (data[0] & 0xff) == 0xf0 && data[1] == 0x7e && data[3] == 0x09 && data[4] == 0x01) {
                    ucsAudioEngine.reset();
                    return;
                }
                try {
                    VaviSynthesizer.processSpecial(sysexMessage, this); // adpcm
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
        if (ucsAudioEngine == null) throw new MidiUnavailableException("not opened");
        return new UcsReceiver(ucsAudioEngine);
    }

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    @Override
    public boolean isOpen() {
        return ucsAudioEngine != null;
    }

    @Override
    public void open() throws MfiUnavailableException {
        try {
            UcsSequencer.waveBank().clear();
            this.ucsAudioEngine = new UcsAudioEngine();
        } catch (IOException e) {
            throw new MfiUnavailableException(e);
        }
    }

    @Override
    public void close() {
        if (ucsAudioEngine != null) {
            ucsAudioEngine.close();
            ucsAudioEngine = null;
        }
    }
}
