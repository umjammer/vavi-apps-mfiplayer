/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

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
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import vavi.sound.midi.MidiConstants;
import vavi.sound.midi.ymf262.YmF262Soundbank.YmF262Instrument;
import vavi.util.ByteUtil;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;


/**
 * MatsuokaSynthesizer.
 * <p>
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/02/25 umjammer initial version <br>
 */
public class MatsuokaSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(MatsuokaSynthesizer.class.getName());

    static {
        try {
            try (InputStream is = MatsuokaSynthesizer.class.getResourceAsStream("/META-INF/maven/vavi/vavi-apps-mfiplayer/pom.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    version = props.getProperty("version", "undefined in pom.properties");
                } else {
                    version = System.getProperty("vavi.test.version", "undefined");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String version;

    /** the device information */
    protected static final Info info =
        new Info("FMF262 MIDI Synthesizer",
                            "vavi",
                            "Software synthesizer for FMF262",
                            "Version " + version) {};

    private final YmF262MidiChannel[] channels = new YmF262MidiChannel[MatsuokaPlayer.SEQUENCER_CHANNELS];

    private final List<VoiceStatus> voiceStatuses = new ArrayList<>();

    private long timestamp;

    private boolean isOpen;

    private final AudioFormat audioFormat = new AudioFormat(44100, 16, 2, true, false);

    private SourceDataLine line;

    private MatsuokaPlayer player;

    // ----

    @Override
    public Info getDeviceInfo() {
        return info;
    }

    @Override
    public void open() throws MidiUnavailableException {
        if (isOpen()) {
logger.log(Level.WARNING, "already open: " + hashCode());
            return;
        }

        for (int i = 0; i < channels.length; i++) {
            channels[i] = new YmF262MidiChannel(i);
        }

        player = new MatsuokaPlayer();
        player.init(audioFormat.getSampleRate());

        //
        isOpen = true;

        init();
        executor.submit(this::play);
    }

    /** when midi spi */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setPriority(Thread.MAX_PRIORITY);
        return thread;
    });

    /** when midi spi */
    private void init() throws MidiUnavailableException {
        try {
            DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);
            line = (SourceDataLine) AudioSystem.getLine(lineInfo);
logger.log(Level.DEBUG, line.getClass().getName());
            line.addLineListener(event -> logger.log(Level.DEBUG, "Line: " + event.getType()));

            line.open(audioFormat);
            line.start();
        } catch (LineUnavailableException e) {
            throw (MidiUnavailableException) new MidiUnavailableException().initCause(e);
        }
    }

    private final int BUF_SIZE = (int) (audioFormat.getSampleRate() / 10);
    private long start;
    private final int[][] buf = new int[audioFormat.getChannels()][BUF_SIZE];
    byte[] sa = new byte[audioFormat.getFrameSize()];

    /** when midi spi */
    private void play() {
//logger.log(Level.TRACE, "buf: %d".formatted(buf.length));
        timestamp = System.currentTimeMillis();
        start = timestamp;

        while (isOpen) {
            try {
                long msec = System.currentTimeMillis() - timestamp;
                timestamp = System.currentTimeMillis();
                msec = Math.min(msec, 100);
                int size = (int) (audioFormat.getSampleRate() * msec / 1000.0);
                size = Math.max(size, 2);
//logger.log(Level.TRACE, "opl3: %d".formatted(size));
                int r = player.read(buf, size);
                for (int i = 0; i < r; i ++) {
                    for (int c = 0; c < audioFormat.getChannels(); c++) {
                        ByteUtil.writeLeShort((short) buf[c][i], sa, c * 2);
                    }
                    line.write(sa, 0, sa.length);
                }
            } catch (Exception e) {
                logger.log(Level.INFO, e.getMessage(), e);
            }
        }
    }

    @Override
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public void close() {
        isOpen = false;
        for (int i = 0; i < receivers.size(); i++) receivers.get(i).close();
        line.drain();
        line.close();
        executor.shutdown();
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public long getMicrosecondPosition() {
        return (timestamp - start) / 10;
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
        return new Opl3Receiver();
    }

    @Override
    public List<Receiver> getReceivers() {
        return receivers;
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
        return 18; // TODO OPL3 class said
    }

    @Override
    public long getLatency() {
        return 33;
    }

    @Override
    public MidiChannel[] getChannels() {
        return channels;
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return voiceStatuses.toArray(VoiceStatus[]::new);
    }

    @Override
    public boolean isSoundbankSupported(Soundbank soundbank) {
        return soundbank instanceof YmF262Instrument;
    }

    @Override
    public boolean loadInstrument(Instrument instrument) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void unloadInstrument(Instrument instrument) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean remapInstrument(Instrument from, Instrument to) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Soundbank getDefaultSoundbank() {
        return player.standards;
    }

    @Override
    public Instrument[] getAvailableInstruments() {
        return player.standards.getInstruments();
    }

    @Override
    public Instrument[] getLoadedInstruments() {
        return player.standards.getInstruments();
    }

    @Override
    public boolean loadAllInstruments(Soundbank soundbank) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void unloadAllInstruments(Soundbank soundbank) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean loadInstruments(Soundbank soundbank, Patch[] patchList) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void unloadInstruments(Soundbank soundbank, Patch[] patchList) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public class YmF262MidiChannel implements MidiChannel {

        private final int channel;

        private int volume;
        private boolean mute;
        private boolean solo;
        private final int[] polyPressure = new int[128];
        private int pressure;
        private int pitchBend;
        private final int[] control = new int[128];

        // instrument number TODO public
        public int program;

        /** */
        public YmF262MidiChannel(int channel) {
            this.channel = channel;
        }

        @Override
        public void noteOn(int noteNumber, int velocity) {
            VoiceStatus voiceStatus = new VoiceStatus();
            voiceStatus.channel = channel;
            voiceStatus.program = program;
            voiceStatus.note = noteNumber;
            voiceStatus.volume = velocity;
            voiceStatus.active = true;
            voiceStatuses.add(voiceStatus);

            player.noteOn(channel, program, noteNumber, velocity);

            //
            this.pressure = velocity;
        }

        @Override
        public void noteOff(int noteNumber, int velocity) {
            VoiceStatus voiceStatus = find(channel, noteNumber);
            if (voiceStatus != null) voiceStatuses.remove(voiceStatus);

            player.noteOff(channel);

            //
            this.pressure = velocity;
        }

        private VoiceStatus find(int channel, int noteNumber) {
            return voiceStatuses.stream().filter(vs -> vs.channel == channel && vs.note == noteNumber).findFirst().orElse(null);
        }

        @Override
        public void noteOff(int noteNumber) {
            noteOff(noteNumber, 0);
        }

        @Override
        public void setPolyPressure(int noteNumber, int pressure) {
            polyPressure[noteNumber] = pressure;
        }

        @Override
        public int getPolyPressure(int noteNumber) {
            return polyPressure[noteNumber];
        }

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
            switch (controller) {
                case 0x07 -> { // channel volume
                    this.volume = value;
logger.log(Level.DEBUG, "control change[%d]: vol(%02x): %d".formatted(channel, controller, value));
                }
                default ->
logger.log(Level.DEBUG, "control change unhandled[%d]: (%02x): %d".formatted(channel, controller, value));
            }

            //
            control[controller] = value;
        }

        @Override
        public int getController(int controller) {
            return control[controller];
        }

        @Override
        public void programChange(int program) {
            this.program = program & 0x7f;
logger.log(Level.DEBUG, "program change[%d]: %d".formatted(channel, program));
        }

        @Override
        public void programChange(int bank, int program) {
            int bankMSB = bank >> 7;
            int bankLSB = bank & 0x7F;
            controlChange(0, bankMSB);
            controlChange(32, bankLSB);
            programChange(program);
        }

        @Override
        public int getProgram() {
            return this.program;
        }

        @Override
        public void setPitchBend(int bend) {
            pitchBend = bend;
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
            controlChange(on ? 126 : 127, 0); // 0x7e, 0x7d
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
            this.mute = mute;
        }

        @Override
        public boolean getMute() {
            return this.mute;
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

    private final List<Receiver> receivers = new ArrayList<>();

    private class Opl3Receiver implements MidiDeviceReceiver {

        private boolean isOpen;

        public Opl3Receiver() {
            receivers.add(this);
            isOpen = true;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!isOpen) throw new IllegalStateException("Receiver is not open");

            switch (message) {
                case ShortMessage shortMessage -> {
                    int channel = shortMessage.getChannel();
                    int command = shortMessage.getCommand();
                    int data1 = shortMessage.getData1();
                    int data2 = shortMessage.getData2();
                    switch (command) {
                        case ShortMessage.NOTE_OFF:
                            channels[channel].noteOff(data1, data2);
                            break;
                        case ShortMessage.NOTE_ON:
//logger.log(Level.DEBUG, "[%d] ch: %d, pr: %d, nt: %d, vl: %d".formatted(timeStamp, channel, channels[channel].program, data1, data2));
                            channels[channel].noteOn(data1, data2);
                            break;
                        case ShortMessage.POLY_PRESSURE:
                            channels[channel].setPolyPressure(data1, data2);
                            break;
                        case ShortMessage.CONTROL_CHANGE:
                            channels[channel].controlChange(data1, data2);
                            break;
                        case ShortMessage.PROGRAM_CHANGE:
                            channels[channel].programChange(data1);
                            break;
                        case ShortMessage.CHANNEL_PRESSURE:
                            channels[channel].setChannelPressure(data1);
                            break;
                        case ShortMessage.PITCH_BEND:
                            channels[channel].setPitchBend(data1 | (data2 << 7));
                            break;
                        default:
                            logger.log(Level.DEBUG, "unhandled short: %02X".formatted(command));
                    }
                }
                case SysexMessage sysexMessage -> {
                    byte[] data = sysexMessage.getData();
logger.log(Level.DEBUG, "sysex: %02X\n%s".formatted(sysexMessage.getStatus(), StringUtil.getDump(data, 32)));
                    switch (data[0]) {
                        case 0x7f -> { // Universal Realtime
                            int c = data[1]; // 0x7f: Disregards channel
                            // Sub-ID, Sub-ID2
                            if (data[2] == 0x04 && data[3] == 0x01) { // Device Control / Master Volume
                                float gain = ((data[4] & 0x7f) | ((data[5] & 0x7f) << 7)) / 16383f;
logger.log(Level.DEBUG, "sysex volume: gain: %3.0f".formatted(gain * 127));
                                volume(line, gain);
                            }
                        }
                        default -> logger.log(Level.DEBUG, "sysex unhandled: %02x".formatted(data[1]));
                    }
                }
                case MetaMessage metaMessage -> {
logger.log(Level.DEBUG, "meta: " + MidiConstants.MetaEvent.valueOf(metaMessage.getType()));
                    switch (metaMessage.getType()) {
                        case 0x2f -> {}
                    }
                }
                case null, default -> {
                    assert false;
                }
            }
        }

        @Override
        public void close() {
            isOpen = false;
            receivers.remove(this);
        }

        @Override
        public MidiDevice getMidiDevice() {
            return MatsuokaSynthesizer.this;
        }
    }
}
