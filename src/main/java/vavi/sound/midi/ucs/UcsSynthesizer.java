/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ucs;

import java.io.IOException;
import java.io.InputStream;
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
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import vavi.sound.ucs.FuetrekRom;
import vavi.sound.ucs.UcsAudioEngine;
import vavi.sound.mfi.ucs.UcsMfiSynthesizer.UcsMfiReceiver;
import vavi.sound.ucs.UcsSequencer;

import static java.lang.System.getLogger;
import static vavi.sound.midi.ucs.UcsMidiDeviceProvider.version;


/**
 * A {@link Synthesizer} that is the fuetrek sound source of the mfi phones (UCS), in pure java.
 * <p>
 * The preset tones are read out of {@code rt_synth_4.dll}, see {@link vavi.sound.ucs.FuetrekRom}.
 * The channel messages go through a {@link UcsMidiChannel}, the exclusives the way
 * {@link UcsMfiReceiver} takes them.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class UcsSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(UcsSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("UCS MIDI Synthesizer",
                     "vavi",
                     "Software synthesizer for the fuetrek sound source of mfi phones",
                     "Version " + version) {};

    private static final int CHANNELS = 16;

    /** the line's buffer [frames], as {@link UcsAudioEngine} opens it */
    private static final int LINE_FRAMES = UcsAudioEngine.BLOCK * 8;

    private final UcsMidiChannel[] channels = new UcsMidiChannel[CHANNELS];

    /** one to a note struck and not off, which is what {@link #getVoiceStatus} is made of */
    private final List<VoiceStatus> voiceStatuses = new CopyOnWriteArrayList<>();

    private final List<Receiver> receivers = new CopyOnWriteArrayList<>();

    private UcsAudioEngine engine;

    /** what the exclusives go to: the mfi values, the universal device controls, gm system on and the adpcm */
    private UcsMfiReceiver exclusives;

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
            UcsSequencer.waveBank().clear();
            open(new UcsAudioEngine());
        } catch (IOException e) {
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }
    }

    /**
     * Opens this without a line: the sound is what is read from the stream returned, rendered
     * when it is read, and a message is taken at the next frame read.
     *
     * @return 32 kHz, 16 bit, stereo, little endian pcm of this, without an end
     */
    public synchronized AudioInputStream openStream() throws MidiUnavailableException {
        if (open) {
            throw new MidiUnavailableException("already open");
        }
        try {
            UcsSequencer.waveBank().clear();
            open(new UcsAudioEngine(FuetrekRom.getInstance(), false));
        } catch (IOException e) {
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }
        UcsAudioEngine engine = this.engine;
        InputStream is = new InputStream() {
            private byte[] pcm = new byte[0];

            @Override
            public int read() {
                throw new UnsupportedOperationException("read by frames");
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (!open) {
                    return -1;
                }
                int frames = len / 4;
                if (frames == 0) {
                    return 0;
                }
                if (pcm.length < frames * 4) pcm = new byte[frames * 4];
                engine.render(pcm, frames);
                System.arraycopy(pcm, 0, b, off, frames * 4);
                return frames * 4;
            }
        };
        AudioFormat format = new AudioFormat(UcsAudioEngine.SAMPLE_RATE, 16, 2, true, false);
        return new AudioInputStream(is, format, AudioSystem.NOT_SPECIFIED);
    }

    /** @param engine a line of its own or none */
    private void open(UcsAudioEngine engine) {
        this.engine = engine;
        exclusives = new UcsMfiReceiver(engine);
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new UcsMidiChannel(i);
        }
        open = true;
        start = System.nanoTime();
logger.log(Level.DEBUG, "ucs: open");
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        exclusives.close();
        exclusives = null;
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
        if (!open) throw new MidiUnavailableException("not opened");
        return new UcsReceiver();
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
        return UcsAudioEngine.POLYPHONY;
    }

    /** the block a message waits for, the block it is taken at the end of, and the line [us] */
    @Override
    public long getLatency() {
        return (long) (UcsAudioEngine.BLOCK * 2 + LINE_FRAMES) * 1_000_000 / UcsAudioEngine.SAMPLE_RATE;
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

    /** @param volume 0 ~ 1, the listener's, a gain after the sound source */
    public void setVolume(double volume) {
        UcsAudioEngine engine = this.engine;
        if (open && engine != null) engine.hostVolume(volume);
    }

    /** the engine when open, null when not */
    private UcsAudioEngine engine() {
        UcsAudioEngine engine = this.engine;
        return open ? engine : null;
    }

    /**
     * One of the sixteen, which remembers what it was told so that asking gets an answer, and
     * passes it on.
     */
    public class UcsMidiChannel implements MidiChannel {

        private final int channel;

        private final int[] control = new int[128];
        private final int[] polyPressure = new int[128];
        private int pressure;
        private int pitchBend = 0x2000;
        private int program;
        private boolean mute;
        private boolean solo;

        UcsMidiChannel(int channel) {
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

            UcsAudioEngine engine = engine();
            if (engine != null) engine.noteOn(channel, noteNumber, velocity);
        }

        @Override
        public void noteOff(int noteNumber, int velocity) {
            voiceStatuses.stream()
                    .filter(v -> v.channel == channel && v.note == noteNumber)
                    .findFirst().ifPresent(voiceStatuses::remove);

            UcsAudioEngine engine = engine();
            if (engine != null) engine.noteOff(channel, noteNumber);
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

        /** the sound source takes it as modulation */
        @Override
        public void setChannelPressure(int pressure) {
            this.pressure = pressure;
            UcsAudioEngine engine = engine();
            if (engine != null) engine.channelPressure(channel, pressure);
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
            UcsAudioEngine engine = engine();
            if (engine != null) engine.controlChange(channel, controller, value);
        }

        @Override
        public int getController(int controller) {
            return control[controller];
        }

        @Override
        public void programChange(int program) {
            this.program = program & 0x7f;
            UcsAudioEngine engine = engine();
            if (engine != null) engine.programChange(channel, this.program);
        }

        /** @param bank the msb is the rom group: 0x79 melody, 0x78 drum, 0x7d, 0x11 (odd melody, even drum), 0 the channel's default */
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
            UcsAudioEngine engine = engine();
            if (engine != null) engine.pitchBend(channel, bend & 0x3fff);
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
     * What a sequencer plays into. A channel message goes through its {@link UcsMidiChannel},
     * an exclusive the way {@link UcsMfiReceiver} takes it: gm system on, the universal device
     * controls, the mfi values of vavi and the adpcm. A meta message is the sequencer's business.
     */
    private class UcsReceiver implements MidiDeviceReceiver {

        private boolean receiverOpen;

        UcsReceiver() {
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
                    UcsMfiReceiver exclusives = UcsSynthesizer.this.exclusives;
                    if (open && exclusives != null) exclusives.send(sysexMessage, timeStamp);
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
            return UcsSynthesizer.this;
        }
    }
}
