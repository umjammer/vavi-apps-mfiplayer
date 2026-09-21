/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.smaf;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

import vavi.sound.midi.VaviMidiDeviceProvider;
import vavi.sound.mobile.AudioEngine;
import vavi.sound.mobile.AudioEngineMixer;
import vavi.sound.mobile.MobileExclusive;
import vavi.sound.smaf.InvalidSmafDataException;
import vavi.sound.smaf.ma5.Ma5Device;
import vavi.sound.smaf.ma5.Ma5Voices;
import vavi.sound.smaf.ma7.Ma7SmafVoices;
import vavi.sound.smaf.vavi.sequencer.WaveSequencer;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;
import static vavi.sound.midi.smaf.SmafMidiDeviceProvider.version;
import static vavi.sound.mobile.MobileExclusive.unpack;


/**
 * A {@link Synthesizer} that is yamaha's MA-5 emulator - {@code M5_EmuSmw5.dll}, what mmftool
 * plays a SMAF song on - playing a SMAF song converted into midi by vavi-sound, live.
 * <p>
 * <b>What is underneath.</b> The dll, a 32 bit x86 Windows one, running on an emulated PC because
 * that is the only place it will run; see {@link Ma5Device}, which is the machine. It is played
 * through the door the dll has for midi as it happens (mmftool's piano roll plays through it), not
 * its file player, so a song is sequenced by whoever plays it here, and this is a synthesizer like
 * any other: channel messages, and the voices and the waves of a song as exclusives.
 * <p>
 * <b>What a song sends.</b> What vavi-sound makes of a song
 * ({@link vavi.sound.smaf.vavi.VaviSmafMidiConverter}), taken as {@link SmafMa7Synthesizer} takes
 * it, see {@link SmafMa5Receiver}: the banks a song selects (0x7c a melody, 0x7d a percussion) are
 * the banks of the dll too, the voices and the waves of the song go to the dll in the MA-3 real
 * time form (an MA-5 one packed into it by {@link Ma7SmafVoices}, as mmftool converts them), and
 * the stream waves of a song are played by the adpcm engines of vavi-sound and mixed into the
 * line, since the dll's midi door has no streams.
 * <p>
 * <b>What that costs.</b> {@link #getLatency} - the emulated sound card, the queue and the line.
 * {@code -Dvavi.sound.smaf.ma5.queue} buys it down at the risk of a gap where the machine could not
 * keep up; {@code -Dvavi.sound.midi.smaf.ma5.line} is the host line's own buffer, in frames.
 * <p>
 * <b>Where the voices come from.</b> The dll initialized for midi has none: they are given to it
 * when this opens, out of mmftool's {@code DefMA3_16.vm3} beside the dll ({@link Ma5Voices}), and
 * a song gives it its own on top. There is no soundbank here to say what they are.
 * <p>
 * <b>What it cannot do.</b> jdosbox keeps its machine in statics, so one of these open is the only
 * one, and nothing else that boots a machine (the faith synthesizers of this jar) may run beside it.
 * <p>
 * {@code vavi.sound.mobile.AudioEngine.disabled} is to be set for a song to keep its own drum kit
 * and to keep its streams off the drum channel, as for {@link SmafMa7Synthesizer}.
 *
 * <h4>system properties</h4>
 * <ul>
 * <li>see {@link Ma5Device}, where the dll is and what it runs at</li>
 * <li>{@code vavi.sound.smaf.ma5.volume} ... how loud the dll plays, 0 ~ 127, default 80: a scale
 *     on the gain a song asks for, the dll clips at its top on a loud song</li>
 * <li>{@code vavi.sound.midi.smaf.ma5.line} ... the host line's buffer [frames], default 2048</li>
 * </ul>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano initial version <br>
 * @see "https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool"
 */
public class SmafMa5LiveSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(SmafMa5LiveSynthesizer.class.getName());

    /** the device information */
    static final Info info =
            new Info("SMAF MA-5 Live MIDI Synthesizer",
                     "vavi",
                     "Software synthesizer for SMAF on the yamaha MA-5 emulator (M5_EmuSmw5.dll)",
                     "Version " + version) {};

    /** midi has sixteen */
    private static final int CHANNELS = 16;

    /** the bank select msb of a melody voice of the dll */
    private static final int MELODY_BANK = 0x7c;

    /** the bank select msb of a percussion voice of the dll, and of the streams of a song */
    private static final int PERCUSSION_BANK = 0x7d;

    /**
     * How loud the dll plays, 0 ~ 127, as a scale on the gain a song asks for.
     * <p>
     * The master volume mmftool sends ({@code 43 79 05 7e 09 vv}, or {@code 06 7e 09} as yamaha's
     * ATS-MA5 does) changes nothing the dll renders here; what does is the gain of the song
     * ({@code 43 79 0x 7f 00 gg}), and the dll clips at its top: "GuitarMan.mmf" asks for 110 and
     * clips 1.3% of its samples at that, none at 80% of it.
     */
    private static final int VOLUME = Integer.getInteger("vavi.sound.smaf.ma5.volume", 80);

    /** the host line's own buffer, which is the last of the latency [frames] */
    private static final int LINE_FRAMES = Integer.getInteger("vavi.sound.midi.smaf.ma5.line", 2048);

    /** what goes out: whatever the dll opens its device as is made this */
    private static final int OUT_CHANNELS = 2;

    private final SmafMa5Channel[] channels = new SmafMa5Channel[CHANNELS];

    /** one to a sounding note, which is what {@link #getVoiceStatus} is made of */
    private final List<VoiceStatus> voiceStatuses = new CopyOnWriteArrayList<>();

    private final List<Receiver> receivers = new CopyOnWriteArrayList<>();

    private final Ma5Device device = new Ma5Device();

    private AudioFormat audioFormat;

    private SourceDataLine line;

    /** carries what the emulated PC makes to the line, and is the only thread that does */
    private Thread pump;

    /** whether the streams of a song are mixed into the line here */
    private boolean mixing;

    /**
     * Where the streams of a song are started and stopped: late by what the dll's notes are late
     * by, so that the two are heard together. One thread, because vavi-sound finds the engine of a
     * stream by the thread it was registered on.
     */
    private ScheduledExecutorService streams;

    private volatile boolean open;

    /** when the machine started, which is where {@link #getMicrosecondPosition} counts from */
    private long start;

    /** the listener's volume, universal master volume, 0 ~ 1 */
    private volatile float hostGain = 1;

    /** the gain the song asks the dll for, 0 ~ 127, the most until it asks */
    private volatile int songGain = 127;

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    /** the machine underneath, for a test that wants to know how it got on */
    Ma5Device getDevice() {
        return device;
    }

    @Override
    public synchronized void open() throws MidiUnavailableException {
        if (open) {
logger.log(Level.WARNING, "already open: " + hashCode());
            return;
        }
        try {
            // the machine first: what the line is, is what the dll opened its device as
            device.open();
            audioFormat = new AudioFormat(device.getFormat().getSampleRate(), 16, OUT_CHANNELS, true, false);
            line = AudioSystem.getSourceDataLine(audioFormat);
            line.open(audioFormat, LINE_FRAMES * audioFormat.getFrameSize());
            line.start();
            volume(line, hostGain);
        } catch (LineUnavailableException | IOException e) {
            closeQuietly();
            throw (MidiUnavailableException) new MidiUnavailableException(e.getMessage()).initCause(e);
        }

        for (int i = 0; i < channels.length; i++) {
            channels[i] = new SmafMa5Channel(i);
        }
        open = true;
        start = System.nanoTime();
        setUp();

        mixing = AudioEngineMixer.attach();
        streams = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "smaf-ma5-streams");
            thread.setDaemon(true);
            return thread;
        });

        pump = new Thread(this::play, "smaf-ma5-pump");
        pump.setDaemon(true);
        pump.setPriority(Thread.MAX_PRIORITY);
        pump.start();
logger.log(Level.DEBUG, "smaf ma5: open, latency " + getLatency() / 1000 + "ms");
    }

    /**
     * What mmftool does before it plays a note on the dll (its {@code InitMidiMode} and
     * {@code EmuMIDIPlay}): the reset, the voices, which the dll has none of ({@link Ma5Voices}),
     * the waves of the last song let go, the melody bank on every channel and the percussion one on
     * the drum channel, and the dll's own volume.
     */
    private void setUp() {
        // what yamaha's own ATS-MA5 sends first (mmftool's memo/M5Emu2.txt), none of it known
        sendYamaha(0x13, 0x08);
        sendYamaha(0x11, 0x00);
        sendYamaha(0x07, 0x00);
        sendYamaha(0x7f);
        Path vm3 = Ma5Device.toolDirectory().toPath().resolve(Ma5Voices.VM3);
        try {
            List<byte[]> voices = Ma5Voices.presets(vm3);
            voices.forEach(device::send);
logger.log(Level.DEBUG, "smaf ma5: " + voices.size() + " voices of " + vm3);
        } catch (IOException e) {
logger.log(Level.WARNING, "smaf ma5: no voices, a note plays nothing but a voice of the song: " + e);
        }
        for (int id = 0; id < 0x80; id++) {
            sendYamaha(0x04, id);
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            int bank = channel == 9 ? PERCUSSION_BANK : MELODY_BANK;
            send(ShortMessage.CONTROL_CHANGE, channel, 0, bank);
            send(ShortMessage.CONTROL_CHANGE, channel, 32, 0);
            send(ShortMessage.PROGRAM_CHANGE, channel, 0, 0);
            channels[channel].control[0] = bank;
        }
        setVolume();
    }

    /**
     * The dll's level, the gain of the song ({@code 43 79 06 7f 00 gg}) scaled by {@link #VOLUME}:
     * a reset takes it back to its default, so it is sent again after one.
     */
    private void setVolume() {
        int gain = songGain * Math.clamp(VOLUME, 0, 127) / 127;
        sendYamaha(0x00, gain);
    }

    /** an exclusive of yamaha to the dll, {@code f0 43 79 06 7f <data> f7} */
    private void sendYamaha(int... data) {
        byte[] message = new byte[6 + data.length];
        message[0] = (byte) 0xf0;
        message[1] = 0x43;
        message[2] = 0x79;
        message[3] = 0x06;
        message[4] = 0x7f;
        for (int i = 0; i < data.length; i++) {
            message[5 + i] = (byte) data[i];
        }
        message[message.length - 1] = (byte) 0xf7;
        device.send(message);
    }

    /**
     * Everything the emulated synthesizer makes, on its way to the line, with the streams of the
     * song mixed in.
     * <p>
     * It never runs dry: what the machine did not have ready is silence, so the line keeps its
     * clock and a moment the emulator was late for is a gap rather than a click.
     */
    private void play() {
        AudioFormat in = device.getFormat();
        int inChannels = in.getChannels();
        int frames = 512;
        byte[] buffer = new byte[frames * in.getFrameSize()];
        short[] samples = new short[frames * OUT_CHANNELS];
        byte[] output = new byte[frames * OUT_CHANNELS * 2];
        while (open) {
            int read = device.read(buffer, 0, buffer.length);
            if (read <= 0) {
                break;
            }
            int n = read / in.getFrameSize();
            for (int i = 0; i < n; i++) {
                int p = i * inChannels * 2;
                short l = (short) ((buffer[p] & 0xff) | (buffer[p + 1] << 8));
                short r = inChannels > 1 ? (short) ((buffer[p + 2] & 0xff) | (buffer[p + 3] << 8)) : l;
                samples[i * 2] = l;
                samples[i * 2 + 1] = r;
            }
            if (mixing) {
                AudioEngineMixer.render(samples, 0, n, audioFormat.getSampleRate());
            }
            for (int i = 0; i < n * OUT_CHANNELS; i++) {
                output[i * 2] = (byte) samples[i];
                output[i * 2 + 1] = (byte) (samples[i] >> 8);
            }
            line.write(output, 0, n * OUT_CHANNELS * 2);
        }
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        // the notes first, so what the machine is still holding is not heard on the way out
        for (SmafMa5Channel channel : channels) {
            if (channel != null) {
                channel.allSoundOff();
            }
        }
        for (Receiver receiver : List.copyOf(receivers)) {
            receiver.close();
        }
        open = false;
        closeQuietly();
        voiceStatuses.clear();
    }

    /** takes down whatever got as far as being up, in the order that unwinds it */
    private void closeQuietly() {
        if (streams != null) {
            streams.shutdownNow();
            streams = null;
        }
        if (mixing) {
            AudioEngineMixer.detach();
            mixing = false;
        }
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
        return new SmafMa5Receiver();
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

    /** not measured on the dll: what an MA-3 / MA-5 driver allots to fm */
    @Override
    public int getMaxPolyphony() {
        return 32;
    }

    /** the emulated sound card, the queue and the line, in microseconds */
    @Override
    public long getLatency() {
        float rate = audioFormat != null ? audioFormat.getSampleRate() : device.getFormat().getSampleRate();
        return device.getLatencyMicros() + (long) (LINE_FRAMES * 1_000_000L / rate);
    }

    @Override
    public MidiChannel[] getChannels() {
        return channels.clone();
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return voiceStatuses.toArray(VoiceStatus[]::new);
    }

    // the voices go to the dll as exclusives, those of DefMA3_16.vm3 and a song's own, and none of
    // them can be read back out

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
        if (command == ShortMessage.PROGRAM_CHANGE || command == ShortMessage.CHANNEL_PRESSURE) {
            device.send(new byte[] {(byte) (command | channel), (byte) data1});
        } else {
            device.send(new byte[] {(byte) (command | channel), (byte) data1, (byte) data2});
        }
    }

    /**
     * One of the sixteen.
     * <p>
     * Every one of these both remembers what it was told - so that asking gets an answer - and
     * passes it on, because the dll keeps its own copy of all this and there is no reading it
     * back out.
     */
    public class SmafMa5Channel implements MidiChannel {

        private final int channel;

        private final int[] control = new int[128];
        private final int[] polyPressure = new int[128];
        private int pressure;
        private int pitchBend = 0x2000;
        private int program;
        private boolean mute;
        private boolean solo;

        SmafMa5Channel(int channel) {
            this.channel = channel;
        }

        @Override
        public void noteOn(int noteNumber, int velocity) {
            if (velocity == 0) {
                noteOff(noteNumber, 0);
                return;
            }
            if (mute) {
                return;
            }
            VoiceStatus voiceStatus = new VoiceStatus();
            voiceStatus.channel = channel;
            voiceStatus.bank = control[0];
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

            // the dll takes a note off as mmftool sends it, a note on of the velocity 0
            send(ShortMessage.NOTE_ON, channel, noteNumber, 0);
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

        /**
         * A bank the dll has not got is taken as it takes it in mmftool: the melody bank, or the
         * percussion one on the drum channel.
         */
        @Override
        public void controlChange(int controller, int value) {
            if (controller == 0 && value != MELODY_BANK && value != PERCUSSION_BANK) {
                value = channel == 9 ? PERCUSSION_BANK : MELODY_BANK;
            }
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
     * What a sequencer plays a SMAF song into, the song converted into midi by vavi-sound.
     * <p>
     * A channel message goes through its {@link SmafMa5Channel}, bar a note which starts a stream
     * of the song. Of the exclusives the universal master volume is the listener's and is answered
     * here, on the line; the smaf exclusives of vavi ({@code f0 45 7f ... f7}, packed) are
     * <ul>
     * <li>{@code 45 03 ...} ... a stream wave of the song, the start and the stop of one: the adpcm
     *     engines of vavi-sound play them, mixed into the line</li>
     * <li>{@code 43 79 0x 7f ...} ... yamaha's own: the voices and the waves of the song to the
     *     dll in the MA-3 real time form ({@link Ma7SmafVoices}), each after the message mmftool
     *     sends before it to clear the place it goes; the reset and {@code 07} as they are</li>
     * </ul>
     * A meta message is the sequencer's business and there is nothing here that wants it.
     *
     * @see vavi.sound.smaf.ma7.Ma7SmafSynthesizer.Ma7SmafReceiver which this follows
     */
    private class SmafMa5Receiver implements MidiDeviceReceiver {

        private static final int KEYS = 128;
        /** stream ids are 1 ~ */
        private static final int STREAMS = 128;

        private boolean receiverOpen;

        /** the format a stream wave came in, -1: there is no wave of the id */
        private final int[] streamFormat = new int[STREAMS];

        /** the stream a note started, 0: none, index channel * {@link #KEYS} + key */
        private final int[] noteStream = new int[CHANNELS * KEYS];

        /** the voices and the waves the song brings of its own, to the dll */
        private final Ma7SmafVoices voices = new Ma7SmafVoices(this::sendVoice);

        SmafMa5Receiver() {
            Arrays.fill(streamFormat, -1);
            receivers.add(this);
            receiverOpen = true;
            if (!AudioEngine.isDisabled()) {
logger.log(Level.WARNING, "vavi.sound.mobile.AudioEngine.disabled is not set: a \"Mobile Standard\" song " +
        "loses the drum kit of its own and its streams share channel 9 with its drums");
            }
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
                        case ShortMessage.NOTE_OFF -> {
                            if (!stopStream(channel, data1)) channels[channel].noteOff(data1, data2);
                        }
                        case ShortMessage.NOTE_ON -> {
                            if (data2 == 0) {
                                if (!stopStream(channel, data1)) channels[channel].noteOff(data1, 0);
                            } else if (!startStream(channel, data1)) {
                                channels[channel].noteOn(data1, data2);
                            }
                        }
                        case ShortMessage.POLY_PRESSURE -> channels[channel].setPolyPressure(data1, data2);
                        case ShortMessage.CONTROL_CHANGE -> {
                            // all sound off, all notes off
                            if (data1 == 120 || data1 == 123) stopStreams(channel);
                            channels[channel].controlChange(data1, data2);
                        }
                        case ShortMessage.PROGRAM_CHANGE -> channels[channel].programChange(data1);
                        case ShortMessage.CHANNEL_PRESSURE -> channels[channel].setChannelPressure(data1);
                        case ShortMessage.PITCH_BEND -> channels[channel].setPitchBend(data1 | (data2 << 7));
                        default ->
logger.log(Level.DEBUG, "unhandled short: %02X".formatted(shortMessage.getStatus()));
                    }
                }
                case SysexMessage sysexMessage -> {
                    byte[] data = sysexMessage.getData();
                    // Universal Realtime, Device Control, Master Volume: the listener's, on the line
                    if (data.length >= 6 && (data[0] & 0xff) == 0x7f && data[2] == 0x04 && data[3] == 0x01) {
                        hostGain = ((data[4] & 0x7f) | ((data[5] & 0x7f) << 7)) / 16383f;
logger.log(Level.DEBUG, "sysex volume: gain: %3.0f".formatted(hostGain * 127));
                        SourceDataLine line = SmafMa5LiveSynthesizer.this.line;
                        if (line != null) {
                            volume(line, hostGain);
                        }
                    } else if (data.length >= 2 && data[0] == VaviMidiDeviceProvider.MANUFACTURER_ID) {
                        try {
                            processSpecial(data);
                        } catch (InvalidSmafDataException | RuntimeException e) {
                            logger.log(Level.ERROR, e.getMessage(), e);
                        }
                    } else {
logger.log(Level.DEBUG, "unhandled sysex:\n" + StringUtil.getDump(data, 32));
                    }
                }
                case MetaMessage metaMessage ->
logger.log(Level.TRACE, "meta: %02x".formatted(metaMessage.getType()));
                case null, default ->
logger.log(Level.DEBUG, "unhandled: " + message);
            }
        }

        /** the smaf exclusives of vavi, see {@link SmafMa5Receiver} */
        private void processSpecial(byte[] data) throws InvalidSmafDataException {
            if (data[1] != MobileExclusive.MIDI_SYSEX_FUNCTION_ID_PACKED) {
logger.log(Level.WARNING, "unhandled function: %02x".formatted(data[1]) + "\n" + StringUtil.getDump(data, 32));
                return;
            }

            byte[] exclusive = unpack(data);
            if (exclusive.length >= 3 && exclusive[0] == VaviMidiDeviceProvider.MANUFACTURER_ID &&
                    exclusive[1] == WaveSequencer.SMAF_SYSEX_FUNCTION_ID_WAVE) {
                int function = exclusive[2] & 0xff;
                if (exclusive.length >= 5 && function == MobileExclusive.WAVE) {
                    // 45 03 10 id format ... : the song has a stream of the id, a note may start it
                    int id = exclusive[3] & 0x7f;
                    streamFormat[id] = exclusive[4] & 0xff;
                }
                WaveSequencer sequencer = WaveSequencer.factory(exclusive);
                byte[] body = Arrays.copyOfRange(exclusive, 2, exclusive.length);
                // a wave is kept now, a start or a stop is heard when the dll's notes are
                stream(function == MobileExclusive.WAVE ? 0 : delay(), () -> {
                    try {
                        sequencer.sequence(body, this);
                    } catch (InvalidSmafDataException e) {
                        logger.log(Level.ERROR, e.getMessage(), e);
                    }
                });
            } else if (isYamaha(exclusive) && ((exclusive[4] & 0xff) == 0x7f || (exclusive[4] & 0xff) == 0x07)) {
                // the reset, and the one mmftool passes without knowing what it is
                byte[] message = sysexOf(exclusive);
                message[3] = 0x06;
                device.send(message);
                if ((exclusive[4] & 0xff) == 0x7f) {
                    // which takes the dll's level back to its default
                    setVolume();
                }
            } else if (isYamaha(exclusive) && (exclusive[4] & 0xff) == 0x00 && exclusive.length >= 6) {
                // the volume the song is to play at, which is the dll's level: see VOLUME
                songGain = exclusive[5] & 0x7f;
logger.log(Level.DEBUG, "smaf ma5: song gain %d".formatted(songGain));
                setVolume();
            } else if (!voices.process(exclusive)) {
logger.log(Level.DEBUG, "unhandled smaf exclusive:\n" + StringUtil.getDump(exclusive, 32));
            }
        }

        /** {@code 43 79 0x 7f nn}, an exclusive of yamaha's of a song */
        private static boolean isYamaha(byte[] exclusive) {
            return exclusive.length >= 5 && (exclusive[0] & 0xff) == 0x43 && (exclusive[1] & 0xff) == 0x79 &&
                    (exclusive[3] & 0xff) == 0x7f;
        }

        /** a yamaha exclusive of a song as a midi one, with the 0xf7 it may have come without */
        private static byte[] sysexOf(byte[] exclusive) {
            int end = (exclusive[exclusive.length - 1] & 0xff) == 0xf7 ? exclusive.length - 1 : exclusive.length;
            byte[] message = new byte[end + 2];
            message[0] = (byte) 0xf0;
            System.arraycopy(exclusive, 0, message, 1, end);
            message[message.length - 1] = (byte) 0xf7;
            return message;
        }

        /**
         * A voice or a wave of the song, {@code f0 43 79 06 7f nn ... f7}, after what mmftool sends
         * before it: a voice the place it goes cleared ({@code 02 mm ll pc dn}), a wave the one of
         * its id let go ({@code 04 id}).
         */
        private void sendVoice(byte[] message) {
            if (message.length > 10 && (message[5] & 0xff) == 0x01) {
                sendYamaha(0x02, message[6] & 0x7f, message[7] & 0x7f, message[8] & 0x7f, message[9] & 0x7f);
            } else if (message.length > 7 && (message[5] & 0xff) == 0x03) {
                sendYamaha(0x04, message[6] & 0x7f);
            }
            device.send(message);
        }

        /**
         * The stream a note starts, 0 when it starts none: a note of the key 0 ~ 12 or 92 ~ 110 on
         * a channel of the percussion bank is a stream of a "Mobile Standard" song, as
         * {@code Note_ON3} of the MA-3 driver plays it, and only the ids the song has a wave of are.
         */
        private int streamId(int channel, int key) {
            if (channels[channel].control[0] != PERCUSSION_BANK) {
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

        /** @return true: the note is a stream and started it, the dll is not to play it */
        private boolean startStream(int channel, int key) {
            int id = streamId(channel, key);
            if (id == 0) {
                return false;
            }
            AudioEngine engine = engine(id);
            if (engine == null) {
                return false; // the dll plays the note, whatever it is of its own
            }
            noteStream[channel * KEYS + key] = id;
logger.log(Level.DEBUG, "stream note: " + channel + "ch, key " + key + " -> stream " + id);
            stream(delay(), () -> AudioEngine.Sync.schedule(() -> engine.start(id)));
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
                stream(delay(), () -> AudioEngine.Sync.scheduleStop(() -> engine.stop(id)));
            }
            return true;
        }

        /** the streams the notes of a channel started, all off at once */
        private void stopStreams(int channel) {
            for (int key = 0; key < KEYS; key++) {
                stopStream(channel, key);
            }
        }

        /** how late the dll's notes are heard, which is when a stream is to be [ms] */
        private long delay() {
            return mixing ? device.getLatencyMicros() / 1000 : 0;
        }

        /** a stream task, on the one thread they all run on */
        private void stream(long delayMillis, Runnable task) {
            ScheduledExecutorService streams = SmafMa5LiveSynthesizer.this.streams;
            if (streams == null || streams.isShutdown()) {
                return;
            }
            streams.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            receiverOpen = false;
            for (int channel = 0; channel < CHANNELS; channel++) {
                stopStreams(channel);
            }
            receivers.remove(this);
        }

        @Override
        public MidiDevice getMidiDevice() {
            return SmafMa5LiveSynthesizer.this;
        }
    }
}
