/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.rohm;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sound.midi.Instrument;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDeviceReceiver;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Patch;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Transmitter;
import javax.sound.midi.VoiceStatus;

import vavi.sound.mfi.rohm.RohmAudioEngine;
import vavi.sound.mfi.rohm.RohmSoundSource;

import static java.lang.System.getLogger;
import static vavi.sound.midi.rohm.RohmMidiDeviceProvider.version;


/**
 * A {@link Synthesizer} that is the rohm sound source of the mfi phones, in pure java.
 * <p>
 * The sound source is a port of faith's {@code rt_synth_2.dll} ("Ring Tone LSI Simulator
 * Type 2"), see {@link vavi.sound.mfi.rohm.RohmSoundSource}, and the rom is read out of that
 * dll where it is installed, see {@link vavi.sound.mfi.rohm.RohmRom}: 64 voices at 44.1 kHz,
 * the melody group 0x79 (bank select msb) and the drums of 0x78 on channel 9.
 * <p>
 * The rom is in the dll, so there is no soundbank here and nothing to load into one.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public class RohmSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(RohmSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("Rohm MIDI Synthesizer",
                     "vavi",
                     "Software synthesizer for the rohm sound source of mfi phones",
                     "Version " + version) {};

    private static final int CHANNELS = RohmSoundSource.CHANNELS;

    /** the line's buffer [frames], as {@link RohmAudioEngine} opens it */
    private static final int LINE_FRAMES = RohmSoundSource.BLOCK * 8;

    private final RohmMidiChannel[] channels = new RohmMidiChannel[CHANNELS];

    /** one to a note struck and not off, which is what {@link #getVoiceStatus} is made of */
    private final List<VoiceStatus> voiceStatuses = new CopyOnWriteArrayList<>();

    private final List<Receiver> receivers = new CopyOnWriteArrayList<>();

    private RohmAudioEngine engine;

    private volatile boolean open;

    /** when it was opened, which is where {@link #getMicrosecondPosition} counts from */
    private long start;

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    @Override
    public synchronized void open() throws MidiUnavailableException {
        if (open) {
logger.log(Level.WARNING, "already open: " + hashCode());
            return;
        }
        try {
            engine = new RohmAudioEngine();
        } catch (IOException e) {
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new RohmMidiChannel(i);
        }
        open = true;
        start = System.nanoTime();
logger.log(Level.DEBUG, "rohm: open");
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        engine.close();
        engine = null;
        for (Receiver receiver : List.copyOf(receivers)) {
            receiver.close();
        }
        voiceStatuses.clear();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public long getMicrosecondPosition() {
        return open ? (System.nanoTime() - start) / 1_000 : 0;
    }

    @Override
    public int getMaxReceivers() {
        return -1;
    }

    @Override
    public int getMaxTransmitters() {
        return 0;
    }

    @Override
    public Receiver getReceiver() throws MidiUnavailableException {
        return new RohmReceiver();
    }

    @Override
    public List<Receiver> getReceivers() {
        return List.copyOf(receivers);
    }

    @Override
    public Transmitter getTransmitter() throws MidiUnavailableException {
        throw new MidiUnavailableException("No transmitter available");
    }

    @Override
    public List<Transmitter> getTransmitters() {
        return Collections.emptyList();
    }

    @Override
    public int getMaxPolyphony() {
        return RohmSoundSource.POLYPHONY;
    }

    /** the block a message waits for, the block it is taken at the end of, and the line [us] */
    @Override
    public long getLatency() {
        return (long) (RohmSoundSource.BLOCK * 2 + LINE_FRAMES) * 1_000_000 / RohmSoundSource.SAMPLE_RATE;
    }

    @Override
    public MidiChannel[] getChannels() {
        return channels.clone();
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return voiceStatuses.toArray(VoiceStatus[]::new);
    }

    // the rom is in the dll: nothing can be read out as a soundbank, and nothing put in

    @Override
    public boolean isSoundbankSupported(Soundbank soundbank) {
        return false;
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
    public Instrument[] getLoadedInstruments() {
        return new Instrument[0];
    }

    @Override
    public boolean loadInstrument(Instrument instrument) {
        return false;
    }

    @Override
    public void unloadInstrument(Instrument instrument) {
    }

    @Override
    public boolean remapInstrument(Instrument from, Instrument to) {
        return false;
    }

    @Override
    public boolean loadAllInstruments(Soundbank soundbank) {
        return false;
    }

    @Override
    public void unloadAllInstruments(Soundbank soundbank) {
    }

    @Override
    public boolean loadInstruments(Soundbank soundbank, Patch[] patchList) {
        return false;
    }

    @Override
    public void unloadInstruments(Soundbank soundbank, Patch[] patchList) {
    }

    /** @param preset the reverb, 0 ~ 7, -1: off, the dll has it off until a gm system on (0, dry) */
    public void setReverb(int preset) {
        if (open) engine.reverb(preset);
    }

    private void send(int command, int channel, int data1, int data2) {
        RohmAudioEngine engine = this.engine;
        if (!open || engine == null) {
            return;
        }
        engine.shortMessage(command | channel, data1, data2);
    }

    /**
     * One of the sixteen, which remembers what it was told so that asking gets an answer, and
     * passes it on.
     */
    public class RohmMidiChannel implements MidiChannel {

        private final int channel;

        private final int[] control = new int[128];
        private final int[] polyPressure = new int[128];
        private int pressure;
        private int pitchBend = 0x2000;
        private int program;
        private boolean mute;
        private boolean solo;

        RohmMidiChannel(int channel) {
            this.channel = channel;
        }

        @Override
        public void noteOn(int noteNumber, int velocity) {
            if (velocity == 0) {
                noteOff(noteNumber, 0);
                return;
            }
            if (mute) return;
            VoiceStatus voiceStatus = new VoiceStatus();
            voiceStatus.channel = channel;
            voiceStatus.program = program;
            voiceStatus.note = noteNumber;
            voiceStatus.volume = velocity;
            voiceStatus.active = true;
            voiceStatuses.add(voiceStatus);

            send(ShortMessage.NOTE_ON, channel, noteNumber, velocity);
        }

        @Override
        public void noteOff(int noteNumber, int velocity) {
            voiceStatuses.stream()
                    .filter(v -> v.channel == channel && v.note == noteNumber)
                    .findFirst().ifPresent(voiceStatuses::remove);

            send(ShortMessage.NOTE_OFF, channel, noteNumber, velocity);
        }

        @Override
        public void noteOff(int noteNumber) {
            noteOff(noteNumber, 0);
        }

        /** the sound source does not take it */
        @Override
        public void setPolyPressure(int noteNumber, int pressure) {
            polyPressure[noteNumber] = pressure;
        }

        @Override
        public int getPolyPressure(int noteNumber) {
            return polyPressure[noteNumber];
        }

        /** the sound source does not take it */
        @Override
        public void setChannelPressure(int pressure) {
            this.pressure = pressure;
        }

        @Override
        public int getChannelPressure() {
            return pressure;
        }

        @Override
        public void controlChange(int controller, int value) {
            control[controller] = value;
            if (controller == 120 || controller == 123) {
                voiceStatuses.removeIf(v -> v.channel == channel);
            }
            send(ShortMessage.CONTROL_CHANGE, channel, controller, value);
        }

        @Override
        public int getController(int controller) {
            return control[controller];
        }

        @Override
        public void programChange(int program) {
            this.program = program & 0x7f;
            send(ShortMessage.PROGRAM_CHANGE, channel, this.program, 0);
        }

        /** @param bank the msb is the group: 0x79 melody, 0x7d, 0x11, an even one a drum set */
        @Override
        public void programChange(int bank, int program) {
            controlChange(0, (bank >> 7) & 0x7f);
            controlChange(32, bank & 0x7f);
            programChange(program);
        }

        @Override
        public int getProgram() {
            return program;
        }

        @Override
        public void setPitchBend(int bend) {
            pitchBend = bend;
            send(ShortMessage.PITCH_BEND, channel, bend & 0x7f, (bend >> 7) & 0x7f);
        }

        @Override
        public int getPitchBend() {
            return pitchBend;
        }

        @Override
        public void resetAllControllers() {
            controlChange(121, 0); // 0x79
        }

        @Override
        public void allNotesOff() {
            controlChange(123, 0); // 0x7b
        }

        @Override
        public void allSoundOff() {
            controlChange(120, 0); // 0x78
        }

        @Override
        public boolean localControl(boolean on) {
            control[122] = on ? 127 : 0;
            return false;
        }

        @Override
        public void setMono(boolean on) {
            control[on ? 126 : 127] = 0;
        }

        @Override
        public boolean getMono() {
            return false;
        }

        @Override
        public void setOmni(boolean on) {
            control[on ? 125 : 124] = 0;
        }

        @Override
        public boolean getOmni() {
            return false;
        }

        @Override
        public void setMute(boolean mute) {
            if (mute && !this.mute) {
                allSoundOff();
            }
            this.mute = mute;
        }

        @Override
        public boolean getMute() {
            return mute;
        }

        @Override
        public void setSolo(boolean soloState) {
            this.solo = soloState;
        }

        @Override
        public boolean getSolo() {
            return solo;
        }
    }

    /**
     * What a sequencer plays into. A channel message goes through its {@link RohmMidiChannel},
     * an exclusive to the sound source, which takes gm system on and the universal device
     * controls, and the mfi values of vavi. A meta message is the sequencer's business.
     */
    private class RohmReceiver implements MidiDeviceReceiver {

        private boolean receiverOpen;

        RohmReceiver() {
            receivers.add(this);
            receiverOpen = true;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!receiverOpen) {
                throw new IllegalStateException("Receiver is not open");
            }
            switch (message) {
                case ShortMessage shortMessage -> {
                    int channel = shortMessage.getChannel();
                    int data1 = shortMessage.getData1();
                    int data2 = shortMessage.getData2();
                    switch (shortMessage.getCommand()) {
                        case ShortMessage.NOTE_OFF -> channels[channel].noteOff(data1, data2);
                        case ShortMessage.NOTE_ON -> channels[channel].noteOn(data1, data2);
                        case ShortMessage.POLY_PRESSURE -> channels[channel].setPolyPressure(data1, data2);
                        case ShortMessage.CONTROL_CHANGE -> channels[channel].controlChange(data1, data2);
                        case ShortMessage.PROGRAM_CHANGE -> channels[channel].programChange(data1);
                        case ShortMessage.CHANNEL_PRESSURE -> channels[channel].setChannelPressure(data1);
                        case ShortMessage.PITCH_BEND -> channels[channel].setPitchBend(data1 | (data2 << 7));
                        default ->
logger.log(Level.DEBUG, "unhandled short: %02X".formatted(shortMessage.getStatus()));
                    }
                }
                case SysexMessage sysexMessage -> {
                    RohmAudioEngine engine = RohmSynthesizer.this.engine;
                    if (open && engine != null && !engine.exclusive(sysexMessage.getMessage())) {
logger.log(Level.DEBUG, "unhandled sysex: " + sysexMessage.getMessage().length + " bytes");
                    }
                }
                case MetaMessage metaMessage ->
logger.log(Level.TRACE, "meta: %02x".formatted(metaMessage.getType()));
                case null, default ->
logger.log(Level.DEBUG, "unhandled: " + message);
            }
        }

        @Override
        public void close() {
            receiverOpen = false;
            receivers.remove(this);
        }

        @Override
        public MidiDevice getMidiDevice() {
            return RohmSynthesizer.this;
        }
    }
}
