/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.faith;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
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
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mfi.faith.FaithType4Device;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;
import static vavi.sound.midi.faith.FaithMidiDeviceProvider.version;


/**
 * A {@link Synthesizer} that is the Type 4 synthesizer out of Faith's "Ring Tone Authoring Tool" -
 * the fuetrek voice engine an MFi phone had in it - played live.
 * <p>
 * <b>What is underneath.</b> {@code rt_synth_4.dll}, a 32 bit x86 Windows dll from 2003, running
 * on an emulated PC because that is the only place it will run; see
 * {@link vavi.sound.mfi.faith.FaithType4Device}, which is the machine, and
 * {@link vavi.sound.mfi.faith.FaithType4Player}, which is the same dll given a whole song at once
 * instead. Everything here does is turn {@code javax.sound.midi} into messages for it and carry
 * what comes back to a line.
 * <p>
 * <b>What that costs.</b> {@link #getLatency} - a few tens of milliseconds, which is the emulated
 * sound card and the line, and is the price of the emulator being allowed to be a little late.
 * {@code -Dvavi.sound.mfi.faith.live.blocks}, {@code .buffers} and {@code .queue} buy it down at
 * the risk of a gap where the machine could not keep up; {@code -Dvavi.sound.midi.faith.line} is
 * the last part of it, the host line's own buffer, in frames.
 * <p>
 * <b>What it cannot do.</b> The dll's voice sets are inside it and cannot be read out or added
 * to, so there is no soundbank here and nothing to load into one. And jdosbox keeps its machine
 * in statics, so one of these open is the only one, and nothing else in this jar that boots a
 * machine may run beside it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-03 nsano initial version <br>
 */
public class FaithSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(FaithSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("Faith Type4 MIDI Synthesizer",
                     "vavi",
                     "Software synthesizer for the fuetrek Type 4 voice engine",
                     "Version " + version) {};

    /** midi has sixteen, and the dll takes them as they come */
    private static final int CHANNELS = 16;

    /** the host line's own buffer, which is the last of the latency [frames] */
    private static final int LINE_FRAMES = Integer.getInteger("vavi.sound.midi.faith.line", 2048);

    private final FaithMidiChannel[] channels = new FaithMidiChannel[CHANNELS];

    /** one to a sounding note, which is what {@link #getVoiceStatus} is made of */
    private final List<VoiceStatus> voiceStatuses = new CopyOnWriteArrayList<>();

    private final List<Receiver> receivers = new CopyOnWriteArrayList<>();

    private final FaithType4Device device = new FaithType4Device();

    private final AudioFormat audioFormat = FaithType4Device.audioFormat();

    private SourceDataLine line;

    /** carries what the emulated PC makes to the line, and is the only thread that does */
    private Thread pump;

    private volatile boolean open;

    /** when the machine started, which is where {@link #getMicrosecondPosition} counts from */
    private long start;

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    /** the machine underneath, for a test that wants to know how it got on */
    FaithType4Device getDevice() {
        return device;
    }

    @Override
    public synchronized void open() throws MidiUnavailableException {
        if (open) {
logger.log(Level.WARNING, "already open: " + hashCode());
            return;
        }
        try {
            line = AudioSystem.getSourceDataLine(audioFormat);
            line.open(audioFormat, LINE_FRAMES * FaithType4Device.FRAME_SIZE);
            line.start();
            device.open();
        } catch (LineUnavailableException | java.io.IOException e) {
            closeQuietly();
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }

        for (int i = 0; i < channels.length; i++) {
            channels[i] = new FaithMidiChannel(i);
        }
        open = true;
        start = System.nanoTime();

        pump = new Thread(this::play, "faith-type4-pump");
        pump.setDaemon(true);
        pump.setPriority(Thread.MAX_PRIORITY);
        pump.start();
logger.log(Level.DEBUG, "faith type4: open, latency " + getLatency() / 1000 + "ms");
    }

    /**
     * Everything the emulated synthesizer makes, on its way to the line.
     * <p>
     * It never runs dry: what the machine did not have ready is silence, so the line keeps its
     * clock and a moment the emulator was late for is a gap rather than the click a line makes
     * when it is left with nothing.
     */
    private void play() {
        byte[] buffer = new byte[1024];
        while (open) {
            int read = device.read(buffer, 0, buffer.length);
            if (read <= 0) {
                break;
            }
            line.write(buffer, 0, read);
        }
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        // the notes first, so what the machine is still holding is not heard on the way out
        for (FaithMidiChannel channel : channels) {
            if (channel != null) {
                channel.allSoundOff();
            }
        }
        open = false;
        closeQuietly();
        for (Receiver receiver : List.copyOf(receivers)) {
            receiver.close();
        }
        voiceStatuses.clear();
    }

    /** takes down whatever got as far as being up, in the order that unwinds it */
    private void closeQuietly() {
        if (pump != null) {
            try {
                pump.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pump = null;
        }
        device.close();
        if (line != null) {
            line.drain();
            line.close();
            line = null;
        }
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
        return new FaithReceiver();
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

    /** 48 voices, or 64 in the third voice set - {@code -Dvavi.sound.mfi.faith.mode=2} */
    @Override
    public int getMaxPolyphony() {
        return FaithType4Device.getMaxPolyphony();
    }

    /** the emulated sound card, the queue and the line, in microseconds */
    @Override
    public long getLatency() {
        return FaithType4Device.getLatencyMicros()
                + (long) LINE_FRAMES * 1_000_000 / (int) audioFormat.getSampleRate();
    }

    @Override
    public MidiChannel[] getChannels() {
        return channels.clone();
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return voiceStatuses.toArray(VoiceStatus[]::new);
    }

    // the dll's voice sets live inside it: they cannot be read out, and nothing can be put in

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

    /** the message, on its way to the emulated PC */
    private void send(int command, int channel, int data1, int data2) {
        if (!open) {
            return;
        }
        device.send(new byte[] {(byte) (command | channel), (byte) data1, (byte) data2});
    }

    /**
     * One of the sixteen.
     * <p>
     * Every one of these both remembers what it was told - so that asking gets an answer - and
     * passes it on, because the dll keeps its own copy of all this and there is no reading it
     * back out.
     */
    public class FaithMidiChannel implements MidiChannel {

        private final int channel;

        private final int[] control = new int[128];
        private final int[] polyPressure = new int[128];
        private int pressure;
        private int pitchBend = 0x2000;
        private int program;
        private boolean mute;
        private boolean solo;

        FaithMidiChannel(int channel) {
            this.channel = channel;
        }

        @Override
        public void noteOn(int noteNumber, int velocity) {
            if (velocity == 0) {
                noteOff(noteNumber, 0);
                return;
            }
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

        @Override
        public void setPolyPressure(int noteNumber, int pressure) {
            polyPressure[noteNumber] = pressure;
            send(ShortMessage.POLY_PRESSURE, channel, noteNumber, pressure);
        }

        @Override
        public int getPolyPressure(int noteNumber) {
            return polyPressure[noteNumber];
        }

        @Override
        public void setChannelPressure(int pressure) {
            this.pressure = pressure;
            send(ShortMessage.CHANNEL_PRESSURE, channel, pressure, 0);
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
            controlChange(122, on ? 127 : 0); // 0x7a
            return getController(122) >= 64;
        }

        @Override
        public void setMono(boolean on) {
            controlChange(on ? 126 : 127, 0); // 0x7e, 0x7f
        }

        @Override
        public boolean getMono() {
            return getController(126) == 0;
        }

        @Override
        public void setOmni(boolean on) {
            controlChange(on ? 125 : 124, 0); // 0x7d, 0x7c
        }

        @Override
        public boolean getOmni() {
            return getController(125) == 0;
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
     * What a sequencer plays into.
     * <p>
     * A channel message goes through its {@link FaithMidiChannel} rather than straight at the
     * machine, so that what was played can be asked about afterwards. An exclusive goes down the
     * dll's own door for one - which is how a UCS voice arrives - bar the one this answers
     * itself, which is the universal master volume. A meta message is the sequencer's business
     * and there is nothing here that wants it.
     */
    private class FaithReceiver implements MidiDeviceReceiver {

        private boolean receiverOpen;

        FaithReceiver() {
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
                    byte[] data = sysexMessage.getData();
                    // Universal Realtime, Device Control, Master Volume - the one exclusive that
                    // is about the listener rather than the voices, so it is answered here
                    if (data.length >= 6 && (data[0] & 0xff) == 0x7f
                            && data[2] == 0x04 && data[3] == 0x01) {
                        float gain = ((data[4] & 0x7f) | ((data[5] & 0x7f) << 7)) / 16383f;
logger.log(Level.DEBUG, "sysex volume: gain: %3.0f".formatted(gain * 127));
                        if (line != null) {
                            volume(line, gain);
                        }
                    } else if (sysexMessage.getStatus() == SysexMessage.SYSTEM_EXCLUSIVE) {
                        device.send(sysexMessage.getMessage());
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
            return FaithSynthesizer.this;
        }
    }
}
