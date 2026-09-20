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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import vavi.sound.mfi.vavi.sequencer.MfiValueExclusive;
import vavi.sound.midi.MidiConstants;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.sound.midi.ymf262.YmF262Soundbank.YmF262Instrument;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;
import vavi.sound.mobile.AudioEngineMixer;
import vavi.util.ByteUtil;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;
import static vavi.sound.midi.ymf262.YmF262MidiDeviceProvider.version;


/**
 * The OPL3 (YMF262) synthesizer of mmfplay, as a MIDI one.
 * <p>
 * The bank it plays is the ".o3" one it is built with, and
 * {@link YmF262MidiDeviceProvider#SOUNDBANK_KEY} names another for it to load instead. What an MFi or SMAF file sends it
 * beyond the notes - the MA-1 ~ MA-5 voices - is {@link YamahaVoices}, the same layer
 * {@link NukedSynthesizer} takes them through, and a wave table voice among those
 * {@link NukedWaveTable}.
 * </p>
 * <p>
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/02/25 umjammer initial version <br>
 *          0.01 2026-09-12 nsano the voices an MFi or SMAF file sends <br>
 * @see "https://github.com/mmontag/mmfplay"
 */
public class MatsuokaSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(MatsuokaSynthesizer.class.getName());

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

    /** wave table (WT) voices, which OPL3 cannot play, see {@link NukedWaveTable} */
    private final NukedWaveTable waveTable = new NukedWaveTable((int) audioFormat.getSampleRate());

    /** the voices an MFi or SMAF file sends, which are not this synthesizer's business */
    private final YamahaVoices yamahaVoices = new YamahaVoices(this::setVoice, waveTable);

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

        YmF262MidiDeviceProvider.loadSoundbank(this);

        //
        isOpen = true;

        init();
        // the adpcm of vavi-sound's engines is mixed into this line, in step with the notes
        mixing = AudioEngineMixer.attach();
        executor.submit(this::play);
    }

    /** whether the adpcm is mixed in here, see {@link AudioEngineMixer#attach()} */
    private boolean mixing;

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
    final byte[] sa = new byte[audioFormat.getFrameSize()];

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
                waveTable.render(buf, r);
                if (mixing) {
                    AudioEngineMixer.render(buf[0], buf[buf.length > 1 ? 1 : 0], r, audioFormat.getSampleRate());
                }
                for (int i = 0; i < r; i ++) {
                    for (int c = 0; c < audioFormat.getChannels(); c++) {
                        ByteUtil.writeLeShort((short) Math.clamp(buf[c][i], Short.MIN_VALUE, Short.MAX_VALUE), sa, c * 2);
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
        waveTable.close();
        line.drain();
        line.close();
        executor.shutdown();
        if (mixing) {
            mixing = false;
            AudioEngineMixer.detach();
        }
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
        return new MatsuokaOpl3Receiver();
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
        return soundbank instanceof YmF262Soundbank;
    }

    /**
     * Sounds a voice an MFi or SMAF file sent as the patch it is for.
     *
     * @see YamahaVoices.Timbres
     */
    private void setVoice(int bank, int program, VM35FMVoice voice) {
        if (player == null) {
logger.log(Level.WARNING, "not open yet, the voice of %d.%d is not sounded".formatted(bank, program));
            return;
        }
        Opl3Instrument instrument = YmF262Soundbank.toInstrument(voice);
        if (bank == 128) {
            player.setDrum(program - 128, instrument);
        } else {
            player.setInstrument(program, instrument);
        }
    }

    /**
     * Sounds one instrument of a {@link YmF262Soundbank}.
     *
     * @param instrument a {@link YmF262Instrument}
     */
    @Override
    public boolean loadInstrument(Instrument instrument) {
        if (!(instrument instanceof YmF262Instrument)) {
            throw new IllegalArgumentException("not an instrument of this synthesizer: " + instrument);
        }
        if (player == null) {
logger.log(Level.WARNING, "not open yet, " + instrument.getPatch() + " is not sounded");
            return false;
        }
        Patch patch = instrument.getPatch();
        Opl3Instrument data = (Opl3Instrument) instrument.getData();
        if (patch.getBank() == 128) {
            player.setDrum(patch.getProgram() - 128, data);
        } else {
            player.setInstrument(patch.getProgram(), data);
        }
        return true;
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

    /** @return false when the soundbank is none of this synthesizer's */
    @Override
    public boolean loadAllInstruments(Soundbank soundbank) {
        if (!isSoundbankSupported(soundbank)) {
logger.log(Level.WARNING, "not a soundbank of this synthesizer, ignored: " +
        soundbank.getName() + ", " + soundbank.getClass().getName());
            return false;
        }
        for (Instrument instrument : soundbank.getInstruments()) {
            loadInstrument(instrument);
        }
logger.log(Level.DEBUG, "bank: " + soundbank.getName() + ", " + soundbank.getInstruments().length + " instruments");
        return true;
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

    /** the listener's volume, universal master volume, 0 ~ 1 */
    private float hostGain = 1;

    /** the song's volume, mfi master volume, 0 ~ 1 */
    private float songGain = 1;

    /** the mfi master volume the universal master volume following is the song's one of, -1: none */
    private int songVolume = -1;

    /**
     * The master volume, the listener's scaled by the song's, see
     * {@link vavi.sound.midi.faith.FaithSynthesizer}.
     * <pre>
     *  f0 45 04 02 vv f7             vavi's mark: the universal master volume following is
     *                                the song's (mfi 0xb0), see {@link MfiValueExclusive}
     *  f0 7f 7f 04 01 ll mm f7       universal master volume, the listener's unless it is
     *                                (00, vv) right after the mark
     * </pre>
     * Else a song which says its volume, as every MFi one does at its top, would take the
     * listener's over.
     *
     * @return false when it is neither
     */
    private synchronized boolean masterVolume(SysexMessage sysexMessage) {
        byte[] data = sysexMessage.getData();
        if (MfiValueExclusive.sub(sysexMessage.getMessage()) == MfiValueExclusive.MASTER_VOLUME && data.length >= 4) {
            songVolume = data[3] & 0x7f;
            songGain = songVolume / 127f;
logger.log(Level.DEBUG, "song volume: %d".formatted(songVolume));
        } else if (data.length >= 6 && (data[0] & 0xff) == 0x7f && data[2] == 0x04 && data[3] == 0x01) {
            if (songVolume >= 0 && data[4] == 0 && data[5] == songVolume) {
                songVolume = -1; // the song's, taken by the mark above
                return true;
            }
            hostGain = ((data[4] & 0x7f) | ((data[5] & 0x7f) << 7)) / 16383f;
logger.log(Level.DEBUG, "sysex volume: gain: %3.0f".formatted(hostGain * 127));
        } else {
            return false;
        }
        if (line != null) {
            volume(line, hostGain * songGain);
        }
        return true;
    }

    private final List<Receiver> receivers = new ArrayList<>();

    private class MatsuokaOpl3Receiver implements MidiDeviceReceiver {

        private boolean isOpen;

        public MatsuokaOpl3Receiver() {
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
                            // a wave table voice or a stream is no timbre, the wave table
                            // plays it instead of the OPL3, see SmafVoices and NukedWaveTable
                            if (waveTable.noteOff(channel, data1)) break;
                            // a note of a smaf drum channel is the OPL3's drum channel's
                            channels[waveTable.oplChannel(channel)].noteOff(data1, data2);
                            break;
                        case ShortMessage.NOTE_ON:
//logger.log(Level.DEBUG, "[%d] ch: %d, pr: %d, nt: %d, vl: %d".formatted(timeStamp, channel, channels[channel].program, data1, data2));
                            if (data2 > 0 ? waveTable.noteOn(channel, data1, data2) : waveTable.noteOff(channel, data1)) break;
                            channels[waveTable.oplChannel(channel)].noteOn(data1, data2);
                            break;
                        case ShortMessage.POLY_PRESSURE:
                            channels[channel].setPolyPressure(data1, data2);
                            break;
                        case ShortMessage.CONTROL_CHANGE:
                            waveTable.controlChange(channel, data1, data2);
                            channels[channel].controlChange(data1, data2);
                            break;
                        case ShortMessage.PROGRAM_CHANGE:
                            waveTable.programChange(channel, data1);
                            channels[channel].programChange(data1);
                            break;
                        case ShortMessage.CHANNEL_PRESSURE:
                            channels[channel].setChannelPressure(data1);
                            break;
                        case ShortMessage.PITCH_BEND:
                            waveTable.pitchBend(channel, data1 | (data2 << 7));
                            channels[channel].setPitchBend(data1 | (data2 << 7));
                            break;
                        default:
                            logger.log(Level.DEBUG, "unhandled short: %02X".formatted(command));
                    }
                }
                case SysexMessage sysexMessage -> {
                    byte[] data = sysexMessage.getData();
                    if (masterVolume(sysexMessage)) {
                        // the listener's or the song's volume, taken
                    } else if (MfiValueExclusive.sub(sysexMessage.getMessage()) >= 0) {
                        // the other mfi values, nothing this takes
                    } else if (!yamahaVoices.process(data)) {
                        // the voices of an MFi or SMAF file are all that is left to take
logger.log(Level.DEBUG, "sysex: %02X\n%s".formatted(sysexMessage.getStatus(), StringUtil.getDump(data, 32)));
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
