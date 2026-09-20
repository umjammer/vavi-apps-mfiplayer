/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma7;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
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

import vavi.sound.mfi.ma7.Ma7AudioEngine;
import vavi.sound.mfi.ma7.Ma7Rom;
import vavi.sound.midi.VaviMidiDeviceProvider;
import vavi.sound.mobile.AudioEngine;
import vavi.sound.mobile.AudioEngineMixer;
import vavi.sound.mobile.MobileExclusive;
import vavi.sound.smaf.InvalidSmafDataException;
import vavi.sound.smaf.SmafUnavailableException;
import vavi.sound.smaf.Synthesizer;
import vavi.sound.smaf.vavi.sequencer.WaveSequencer;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.mobile.MobileExclusive.unpack;


/**
 * Ma7SmafSynthesizer.
 * <p>
 * The yamaha MA-7 in pure java ({@link Ma7AudioEngine}) as the sound source of a SMAF song, the rom
 * is read out of the installed {@code libM7_EmuSmw7.so}, see {@link Ma7Rom}. The stream waves of a
 * song ("Mwa*", "Awa*") come as the exclusives of vavi ({@link MobileExclusive}, packed 8 bit into 7
 * as every smaf exclusive is) and are played by the adpcm engines of vavi-sound, mixed into the
 * engine's line, see {@link Ma7SmafReceiver}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class Ma7SmafSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(Ma7SmafSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("Java SMAF MA-7 Synthesizer",
                    "vavi",
                    "SMAF yamaha MA-7 synthesizer with ADPCM",
                    "Version " + Ma7SmafDeviceProvider.version) {};

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
     * The channel messages go to the sound source as they come: the banks a SMAF song selects (the
     * bank select msb 0x7c a melody, 0x7d a percussion) are the banks the MA-7 driver's real time
     * midi path knows. Of the exclusives the engine takes the universal ones (gm system on, the
     * master volume and the tunings), the rest is the smaf exclusives of vavi, see
     * {@link #processSpecial}.
     * <p>
     * The streams of a song are the one thing the sound source has nothing of yet, and they are
     * played by the adpcm engines of vavi-sound here, mixed into the engine's line
     * ({@link AudioEngineMixer}) so that they sound in the song and not beside it. A "Handy Phone
     * Standard" song starts them by an exclusive of vavi ({@link WaveSequencer}), a "Mobile
     * Standard" one by a note, see {@link #streamId}.
     *
     * @see vavi.sound.smaf.vavi.VaviSmafSynthesizer.VaviSmafReceiver
     */
    public static class Ma7SmafReceiver implements MidiDeviceReceiver {

        /** the bank select msb of the percussion voices and the streams */
        private static final int STREAM_BANK = 0x7d;
        private static final int CHANNELS = 16;
        private static final int KEYS = 128;
        /** stream ids are 1 ~ */
        private static final int STREAMS = 128;

        private boolean isOpen = true;

        /** */
        private final Ma7AudioEngine audioEngine;

        /** the bank select msb of a channel */
        private final int[] bankMsb = new int[CHANNELS];

        /** the format a stream wave came in, -1: there is no wave of the id */
        private final int[] streamFormat = new int[STREAMS];

        /** the stream a note started, 0: none, index channel * {@link #KEYS} + key */
        private final int[] noteStream = new int[CHANNELS * KEYS];

        public Ma7SmafReceiver(Ma7AudioEngine audioEngine) {
            this.audioEngine = audioEngine;
            Arrays.fill(streamFormat, -1);
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!isOpen) return;

            if (message instanceof ShortMessage shortMessage) {
                int status = shortMessage.getStatus();
                int channel = status & 0x0f;
                int data1 = shortMessage.getData1();
                int data2 = shortMessage.getData2();
                switch (status & 0xf0) {
                    case ShortMessage.NOTE_ON -> {
                        if (data2 != 0) {
                            if (startStream(channel, data1)) return;
                        } else {
                            if (stopStream(channel, data1)) return;
                        }
                    }
                    case ShortMessage.NOTE_OFF -> {
                        if (stopStream(channel, data1)) return;
                    }
                    case ShortMessage.CONTROL_CHANGE -> {
                        if (data1 == 0) bankMsb[channel] = data2 & 0x7f;
                        // all sound off, all notes off
                        if (data1 == 120 || data1 == 123) stopStreams(channel);
                    }
                }
                audioEngine.shortMessage(status, data1, data2);
            } else if (message instanceof SysexMessage sysexMessage) {
                if (audioEngine.exclusive(sysexMessage.getMessage())) return;
                // the adpcm is mixed into the engine's line, which has to be there by now
                audioEngine.startOutput();
                try {
                    processSpecial(sysexMessage);
                } catch (InvalidSmafDataException | RuntimeException e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
            }
        }

        /**
         * The smaf exclusives, which are the exclusives of the song packed 8 bit into 7
         * ({@code f0 45 7f ... f7}, {@link MobileExclusive#pack}):
         * <ul>
         * <li>{@code 45 03 ...} ... a stream wave of vavi, its data and the start and the stop of
         *     it, played by the adpcm engines of vavi-sound ({@link WaveSequencer})</li>
         * <li>{@code 43 ...} ... yamaha's own, the voices and the waves of a song and the like,
         *     which the MA-7 does not take yet: its own voices are played instead</li>
         * </ul>
         * {@code vavi.sound.mobile.AudioEngine.disabled} is to be off (the default) for the waves
         * to come this way, see {@link AudioEngine#isDisabled}.
         */
        private void processSpecial(SysexMessage message) throws InvalidSmafDataException {

            byte[] data = message.getData();
            if (data.length < 2 || data[0] != VaviMidiDeviceProvider.MANUFACTURER_ID) {
logger.log(Level.DEBUG, "unhandled manufacturer: %02x".formatted(data[0]) + "\n" + StringUtil.getDump(data, 32));
                return;
            }
            if (data[1] != MobileExclusive.MIDI_SYSEX_FUNCTION_ID_PACKED) {
logger.log(Level.WARNING, "unhandled function: %02x".formatted(data[1]) + "\n" + StringUtil.getDump(data, 32));
                return;
            }

            byte[] exclusive = unpack(data);
            if (exclusive.length >= 3 && exclusive[0] == VaviMidiDeviceProvider.MANUFACTURER_ID &&
                    exclusive[1] == WaveSequencer.SMAF_SYSEX_FUNCTION_ID_WAVE) {
                if (exclusive.length >= 5 && (exclusive[2] & 0xff) == MobileExclusive.WAVE) {
                    // 45 03 10 id format ... : the song has a stream of the id, a note may start it
                    int id = exclusive[3] & 0x7f;
                    if (id < STREAMS) streamFormat[id] = exclusive[4] & 0xff;
                }
                WaveSequencer sequencer = WaveSequencer.factory(exclusive);
                sequencer.sequence(Arrays.copyOfRange(exclusive, 2, exclusive.length), this);
            } else {
logger.log(Level.DEBUG, "unhandled smaf exclusive: %02x".formatted(exclusive[0]) + "\n" + StringUtil.getDump(exclusive, 32));
            }
        }

        /**
         * The stream a note starts, 0 when it starts none: a note of the key 0 ~ 12 or 92 ~ 110 on
         * a channel of the bank select msb {@link #STREAM_BANK} is a stream of a "Mobile Standard"
         * song, as {@code Note_ON3} of the MA-3 driver ({@code mammfcnv.c}) plays it, and only the
         * ids the song has a wave of are one.
         *
         * @see MobileExclusive
         */
        private int streamId(int channel, int key) {
            if (bankMsb[channel] != STREAM_BANK) {
                return 0;
            }
            int id = key <= 12 ? key + 1 : key >= 92 ? key - 78 : 0;
            return id < STREAMS && streamFormat[id] >= 0 ? id : 0;
        }

        /** the adpcm engine of a stream wave, null: none of them takes the format it came in */
        private AudioEngine engine(int id) {
            try {
                return WaveSequencer.AudioEngineFactory.getAudioEngine(streamFormat[id]);
            } catch (IllegalArgumentException e) {
logger.log(Level.WARNING, "no audio engine for the format %02x of the stream %d".formatted(streamFormat[id], id));
                return null;
            }
        }

        /** @return true: the note is a stream and started it, the sound source is not to play it */
        private boolean startStream(int channel, int key) {
            int id = streamId(channel, key);
            if (id == 0) {
                return false;
            }
            AudioEngine engine = engine(id);
            if (engine == null) {
                return false; // the sound source plays the note, whatever it is of its own
            }
            // the adpcm goes into the engine's line, which has to be there before the engine starts
            audioEngine.startOutput();
            noteStream[channel * KEYS + key] = id;
logger.log(Level.DEBUG, "stream note: " + channel + "ch, key " + key + " -> stream " + id);
            AudioEngine.Sync.schedule(() -> engine.start(id));
            return true;
        }

        /** @return true: the note was a stream and stopped it */
        private boolean stopStream(int channel, int key) {
            int id = noteStream[channel * KEYS + key];
            if (id == 0) {
                return false;
            }
            noteStream[channel * KEYS + key] = 0;
            AudioEngine engine = engine(id);
            if (engine != null) {
                AudioEngine.Sync.scheduleStop(() -> engine.stop(id));
            }
            return true;
        }

        /** the streams the notes of a channel started, all off at once */
        private void stopStreams(int channel) {
            for (int key = 0; key < KEYS; key++) {
                stopStream(channel, key);
            }
        }

        @Override
        public void close() {
            isOpen = false;
            for (int channel = 0; channel < CHANNELS; channel++) {
                stopStreams(channel);
            }
        }

        @Override
        public MidiDevice getMidiDevice() {
            return null;
        }
    }

    @Override
    public Receiver getReceiver() throws MidiUnavailableException {
        if (audioEngine == null) throw new MidiUnavailableException("not opened");
        return new Ma7SmafReceiver(audioEngine);
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
    public void open() throws SmafUnavailableException {
        try {
            this.audioEngine = new Ma7AudioEngine();
        } catch (IOException e) {
            throw new SmafUnavailableException(e);
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
