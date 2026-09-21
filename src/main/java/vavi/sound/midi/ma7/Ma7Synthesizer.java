/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ma7;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sound.midi.Instrument;
import javax.sound.midi.InvalidMidiDataException;
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

import vavi.sound.ma7.Ma7AudioEngine;
import vavi.sound.mfi.ma7.Ma7MfiSynthesizer.Ma7MfiReceiver;
import vavi.sound.ma7.Ma7Rom;
import vavi.sound.ma7.Ma7SoundSource;

import static java.lang.System.getLogger;
import static vavi.sound.midi.ma7.Ma7MidiDeviceProvider.version;


/**
 * A {@link Synthesizer} that is the ma7 sound source of the mfi phones, in pure java.
 * <p>
 * The sound source is a port of the MA-7 emulator of yamaha's {@code libM7_EmuSmw7.so} with its
 * driver's real time midi path, see {@link vavi.sound.ma7.Ma7SoundSource}, and the rom is read
 * out of that library where it is, see {@link vavi.sound.ma7.Ma7Rom}: 32 fm and 32 wave table
 * voices at 48 kHz, the gm melody bank 0x79 (bank select msb) and the drums of 0x78 on channel 9.
 * <p>
 * The messages of a sequence go the way {@link Ma7MfiReceiver} takes them, the channel ones
 * through a {@link Ma7MidiChannel} first.
 * <p>
 * The rom is in the library, so there is no soundbank here and nothing to load into one.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class Ma7Synthesizer implements Synthesizer {

    private static final Logger logger = getLogger(Ma7Synthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("MA-7 MIDI Synthesizer",
                     "vavi",
                     "Software synthesizer for the yamaha MA-7 of mobile phones",
                     "Version " + version) {};

    private static final int CHANNELS = Ma7SoundSource.CHANNELS;

    /** the line's buffer [frames], as {@link Ma7AudioEngine} opens it */
    private static final int LINE_FRAMES = Ma7AudioEngine.BLOCK * 8;

    private final Ma7MidiChannel[] channels = new Ma7MidiChannel[CHANNELS];

    /** one to a note struck and not off, which is what {@link #getVoiceStatus} is made of */
    private final List<VoiceStatus> voiceStatuses = new CopyOnWriteArrayList<>();

    private final List<Receiver> receivers = new CopyOnWriteArrayList<>();

    private Ma7AudioEngine engine;

    /**
     * what the messages of a sequence go to: the sound source, and what the receiver of the file's
     * kind makes of them - the mfi values, gm system on, the universal device controls and the adpcm
     */
    private Receiver engineReceiver;

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
            open(new Ma7AudioEngine());
        } catch (IOException e) {
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }
    }

    /**
     * Opens this without a line: the sound is what is read from the stream returned, rendered
     * when it is read, and a message is taken at the next frame read.
     *
     * The stream waves of a song are not mixed in: the caller mixes them into what it renders
     * itself, see {@link #openStream(boolean)}.
     *
     * @return 48 kHz, 16 bit, stereo, little endian pcm of this, without an end
     */
    public AudioInputStream openStream() throws MidiUnavailableException {
        return openStream(false);
    }

    /**
     * Opens this without a line, see {@link #openStream()}.
     *
     * @param streams true: the stream waves of a song are mixed into the pcm returned, in the same
     *                block as the messages that start them and before anything is cut to 16 bit;
     *                the caller must not mix them too. false: the caller mixes them, or they play
     *                to lines of their own, out of step with what is read here
     * @return 48 kHz, 16 bit, stereo, little endian pcm of this, without an end
     */
    public synchronized AudioInputStream openStream(boolean streams) throws MidiUnavailableException {
        if (open) {
            throw new MidiUnavailableException("already open");
        }
        try {
            open(new Ma7AudioEngine(Ma7Rom.getInstance(), false, streams));
        } catch (IOException e) {
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }
        Ma7AudioEngine engine = this.engine;
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
        AudioFormat format = new AudioFormat(Ma7AudioEngine.SAMPLE_RATE, 16, 2, true, false);
        return new AudioInputStream(is, format, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * The receiver the messages of a sequence go to, which is what tells an mfi song from a smaf
     * one: {@link Ma7MfiReceiver} sends the channel messages to the sound source and takes the mfi
     * values of vavi and vavi's mfi adpcm, see {@link vavi.sound.midi.smaf.SmafMa7Synthesizer} for
     * the smaf one.
     *
     * @param engine the engine this is open on
     */
    protected Receiver receiver(Ma7AudioEngine engine) {
        return new Ma7MfiReceiver(engine);
    }

    /** @param engine a line of its own or none */
    private void open(Ma7AudioEngine engine) {
        this.engine = engine;
        engineReceiver = receiver(engine);
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Ma7MidiChannel(i);
        }
        open = true;
        start = System.nanoTime();
logger.log(Level.DEBUG, "ma7: open");
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        engineReceiver.close();
        engineReceiver = null;
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
        return new Ma7Receiver();
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
        return Ma7SoundSource.POLYPHONY;
    }

    /** the block being rendered and the line [us] */
    @Override
    public long getLatency() {
        return (long) (Ma7AudioEngine.BLOCK + LINE_FRAMES) * 1_000_000 / Ma7SoundSource.SAMPLE_RATE;
    }

    @Override
    public MidiChannel[] getChannels() {
        return channels.clone();
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return voiceStatuses.toArray(VoiceStatus[]::new);
    }

    // the rom is in the library: nothing can be read out as a soundbank, and nothing put in

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

    private void send(int command, int channel, int data1, int data2) {
        Receiver receiver = this.engineReceiver;
        if (!open || receiver == null) {
            return;
        }
        try {
            ShortMessage message = new ShortMessage();
            message.setMessage(command | channel, data1, data2);
            receiver.send(message, -1);
        } catch (InvalidMidiDataException e) {
logger.log(Level.DEBUG, "%02x %02x %02x".formatted(command | channel, data1, data2) + ": " + e);
        }
    }

    /**
     * One of the sixteen, which remembers what it was told so that asking gets an answer, and
     * passes it on.
     */
    public class Ma7MidiChannel implements MidiChannel {

        private final int channel;

        private final int[] control = new int[128];
        private final int[] polyPressure = new int[128];
        private int pressure;
        private int pitchBend = 0x2000;
        private int program;
        private boolean mute;
        private boolean mono;
        private boolean solo;

        Ma7MidiChannel(int channel) {
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
            if (controller == 120 || controller == 123 || controller == 126 || controller == 127) {
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

        /** @param bank the msb: 0x79 melody, 0x78 drums, 0x7c, 0x7d the banks of the library's table */
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

        /** the sound source takes mono (cc 126, 1) and poly (cc 127) */
        @Override
        public void setMono(boolean on) {
            this.mono = on;
            controlChange(on ? 126 : 127, on ? 1 : 0);
        }

        @Override
        public boolean getMono() {
            return mono;
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
     * What a sequencer plays into. A channel message goes through its {@link Ma7MidiChannel},
     * an exclusive to {@link Ma7MfiReceiver}: the sound source takes gm system on, the universal
     * device controls and the mfi values of vavi, the rest is vavi's adpcm. A meta message is the
     * sequencer's business.
     */
    private class Ma7Receiver implements MidiDeviceReceiver {

        private boolean receiverOpen;

        Ma7Receiver() {
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
                    Receiver receiver = Ma7Synthesizer.this.engineReceiver;
                    if (open && receiver != null) receiver.send(sysexMessage, timeStamp);
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
            return Ma7Synthesizer.this;
        }
    }
}
