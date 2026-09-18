/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.midi.Instrument;
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

import vavi.sound.midi.ymf262.NukedSoundbank.NukedInstrument;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.sound.midi.ymf262.YmF262Soundbank.YmF262Instrument;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;
import vavi.util.ByteUtil;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;
import static vavi.sound.midi.ymf262.YmF262MidiDeviceProvider.version;


/**
 * The OPL3 (YMF262) synthesizer of the Nuked driver, as a MIDI one.
 * <p>
 * The bank it plays is the OPL3 one the driver comes with, and
 * {@link YmF262MidiDeviceProvider#SOUNDBANK_KEY} names another for it to load instead. What an MFi or SMAF file
 * sends it beyond the notes - the MA-1 ~ MA-5 voices - is {@link YamahaVoices}, and a wave
 * table voice among those {@link NukedWaveTable}: neither is a YMF262 matter, this only
 * hands them what its receiver takes.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/03/12 umjammer initial version <br>
 *          0.01 2026-09-11 nsano wave table voices, fix the 8 to 7 bit unpacking <br>
 *          0.02 2026-09-12 nsano a soundbank of the spi to play, the voices an MFi or
 *                                   SMAF file sends are {@link YamahaVoices} now <br>
 * @see "https://github.com/nukeykt/WinOPL3Driver"
 */
public class NukedSynthesizer implements Synthesizer {

    private static final Logger logger = getLogger(NukedSynthesizer.class.getName());

    /** the device information */
    protected static final Info info =
        new Info("Nuked OPL3 MIDI Synthesizer",
                            "vavi",
                            "Nuked Software synthesizer for OPL3",
                            "Version " + version) {};

    private long timestamp;

    private boolean isOpen;

    private final AudioFormat audioFormat = new AudioFormat(44100, 16, 2, true, false);

    private SourceDataLine line;

    private NukedPlayer player;

    private final NukedSoundbank soundbank = new NukedSoundbank();

    /** wave table (WT) voices and stream pcm, which OPL3 cannot play, see {@link NukedWaveTable} */
    private final NukedWaveTable waveTable = new NukedWaveTable((int) audioFormat.getSampleRate());

    /** the voices an MFi or SMAF file sends, which are not this synthesizer's business */
    private final YamahaVoices yamahaVoices = new YamahaVoices(new YamahaVoices.Timbres() {
        @Override public void setVoice(int bank, int program, VM35FMVoice voice) {
            NukedSynthesizer.this.setVoice(bank, program, voice);
        }
        @Override public void setVoice(int bankMSB, int bankLSB, int program, int drumNote, VM35FMVoice voice) {
            NukedSynthesizer.this.setVoice(bankMSB, bankLSB, program, drumNote, voice);
        }
    }, waveTable);

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

        player = new NukedPlayer();
        player.midi_init((int) audioFormat.getSampleRate());

        YmF262MidiDeviceProvider.loadSoundbank(this);

        //
        isOpen = true;

        init();
        executor.submit(this::play);
    }

    /** when midi spi */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** when midi spi */
    private void init() throws MidiUnavailableException {
        try {
            DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);
            line = (SourceDataLine) AudioSystem.getLine(lineInfo);
logger.log(Level.DEBUG, line.getClass().getName());
            line.addLineListener(event -> logger.log(Level.DEBUG, "Line: " + event.getType()));

            // Get the line buffer size for reference
            int lineBufferSize = line.getBufferSize();
            line.open(audioFormat, lineBufferSize * 2);
            line.start();
        } catch (LineUnavailableException e) {
            throw (MidiUnavailableException) new MidiUnavailableException().initCause(e);
        }
    }

    private long start;
    private static final int audioBufferTimeMs = 100; // 100ms audio buffer
    private final int bufferSizeInBytes = (int) (audioFormat.getSampleRate() * audioFormat.getChannels() * 2 * audioBufferTimeMs / 1000.0);
    private final int[][] buf = new int[audioFormat.getChannels()][bufferSizeInBytes];

    /**
     * when midi spi
     *
     * @see "https://claude.ai/chat/f24ee70d-b639-49dd-99ac-437eff189ce5"
     */
    private void play() {
        timestamp = System.currentTimeMillis();
        start = timestamp;

        // Use a significantly larger buffer for ultra-smooth playback
        byte[] lineBuffer = new byte[bufferSizeInBytes];

        // Pre-fill the buffer to avoid startup issues
        int initialSamples = bufferSizeInBytes / (audioFormat.getChannels() * 2);
        player.midi_generate(buf, initialSamples);
        int lineBufferPos = 0;
        for (int i = 0; i < initialSamples; i++) {
            for (int c = 0; c < audioFormat.getChannels(); c++) {
                ByteUtil.writeLeShort((short) buf[c][i], lineBuffer, lineBufferPos + (c * 2));
            }
            lineBufferPos += audioFormat.getChannels() * 2;
        }
        line.write(lineBuffer, 0, lineBufferPos);

        // Critical: Precise sample counting to maintain consistent timing
        float sampleRate = audioFormat.getSampleRate();
        double samplesPerMs = sampleRate / 1000.0;

        // Track total samples for perfect timing
        long totalSamplesGenerated = initialSamples;
        long startTimeNanos = System.nanoTime();

        // For consistency, use a fixed chunk size
        final int fixedChunkSizeMs = 5; // Smaller chunks for more consistent timing
        int fixedSamplesToGenerate = (int) (sampleRate * fixedChunkSizeMs / 1000.0);

        while (isOpen) {
            try {
                long cycleStartTime = System.nanoTime();

                // Check exactly how many samples should have been generated by now based on elapsed time
                long elapsedTimeNanos = cycleStartTime - startTimeNanos;
                double elapsedTimeMs = elapsedTimeNanos / 1_000_000.0;
                long expectedSampleCount = (long) (elapsedTimeMs * samplesPerMs);

                // Calculate how many samples we need to generate to catch up precisely
                long sampleDeficit = expectedSampleCount - totalSamplesGenerated;

                // Maintain a minimum safe sample generation size
                int samplesToGenerate = (int) Math.max(fixedSamplesToGenerate, sampleDeficit);

                // Cap to reasonable size to prevent flooding the buffer
                samplesToGenerate = Math.min(samplesToGenerate, fixedSamplesToGenerate * 3);

                // Generate audio data
                player.midi_generate(buf, samplesToGenerate);
                waveTable.render(buf, samplesToGenerate);

                // Process samples
                lineBufferPos = 0;
                for (int i = 0; i < samplesToGenerate; i++) {
                    for (int c = 0; c < audioFormat.getChannels(); c++) {
                        ByteUtil.writeLeShort((short) buf[c][i], lineBuffer, lineBufferPos + (c * 2));
                    }
                    lineBufferPos += audioFormat.getChannels() * 2;
                }

                // Write all samples at once
                if (lineBufferPos > 0) {
                    line.write(lineBuffer, 0, lineBufferPos);
                }

                // Update our sample count - critical for maintaining steady playback rate
                totalSamplesGenerated += samplesToGenerate;

                // Calculate the theoretically perfect time for the next cycle
                // This is key to eliminating wow and flutter - we base timing on sample count, not real time
                long idealNextCycleTimeNanos = startTimeNanos + (long) ((totalSamplesGenerated / samplesPerMs) * 1_000_000);
                long timeToNextCycleNanos = idealNextCycleTimeNanos - System.nanoTime();

                // Sleep until the next ideal cycle time, but never go negative
                if (timeToNextCycleNanos > 0) {
                    if (timeToNextCycleNanos > 2_000_000) { // If more than 2ms
                        Thread.sleep(timeToNextCycleNanos / 1_000_000);
                        // Fine-grained waiting for the remainder
                        long refinedWaitUntil = idealNextCycleTimeNanos - 500_000; // Wake 0.5ms early
                        while (System.nanoTime() < refinedWaitUntil) {
                            Thread.yield(); // Less aggressive than pure busy-wait
                        }
                    }

                    // Final precise busy-wait
                    while (System.nanoTime() < idealNextCycleTimeNanos) {
                        // Pure busy-wait for final microsecond precision
                    }
                } else if (timeToNextCycleNanos < -10_000_000) { // If we're more than 10ms behind
                    // We're falling behind - adjust our timing reference point to avoid perpetual catch-up
                    // This prevents buffer starvation while maintaining rate stability
                    long adjustmentNanos = timeToNextCycleNanos + 5_000_000; // Recover more gradually (keep 5ms of the deficit)
                    startTimeNanos -= adjustmentNanos;
                    logger.log(Level.TRACE, "Timing adjusted to prevent starvation: " + (adjustmentNanos / 1_000_000) + "ms");
                }

                // Update timestamp for compatibility with existing code
                timestamp = System.currentTimeMillis();

            } catch (Exception e) {
                logger.log(Level.INFO, "Audio processing error: " + e.getMessage(), e);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
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
    }

    @Override
    public boolean isOpen() {
        return false;
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
        return new NuledOpl3Receiver();
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
        return null;
    }

    @Override
    public VoiceStatus[] getVoiceStatus() {
        return null;
    }

    @Override
    public boolean isSoundbankSupported(Soundbank soundbank) {
        // the OPL3 bank of a ".sbi", ".o3" or ".vm3" file is the same registers in the
        // shape the other player of this package wants them, see NukedSoundbank#toTimbre
        return soundbank instanceof NukedSoundbank || soundbank instanceof YmF262Soundbank;
    }

    /**
     * Sounds one instrument of a {@link NukedSoundbank}, or of a {@link YmF262Soundbank} -
     * the OPL3 bank a ".sbi", ".o3" or ".vm3" file is read into, {@link Vm3SoundbankReader}
     * being where the last comes from.
     *
     * @param instrument a {@link NukedInstrument} or a {@link YmF262Instrument}
     */
    @Override
    public boolean loadInstrument(Instrument instrument) {
        NukedInstrument nuked = switch (instrument) {
            case NukedInstrument i -> i;
            case YmF262Instrument i -> new NukedInstrument(
                    i.getPatch().getBank(),
                    i.getPatch().getProgram(),
                    i.getPatch().getBank() == 128,
                    NukedSoundbank.toTimbre((Opl3Instrument) i.getData()),
                    i.getName());
            default ->
                    throw new IllegalArgumentException("not an instrument of this synthesizer: " + instrument);
        };
        soundbank.setInstrument(nuked.getPatch(), nuked);
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
        return soundbank;
    }

    @Override
    public Instrument[] getAvailableInstruments() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Instrument[] getLoadedInstruments() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * Sounds every instrument of a {@link NukedSoundbank}, which is how the MA-3 preset
     * voices of a ".vm3" arrive, see {@link YmF262MidiDeviceProvider#SOUNDBANK_KEY}.
     *
     * @return false when the soundbank is none of this synthesizer's
     */
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

    private final List<Receiver> receivers = new ArrayList<>();

    private class NuledOpl3Receiver implements MidiDeviceReceiver {

        private boolean isOpen;

        public NuledOpl3Receiver() {
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
                        case ShortMessage.PROGRAM_CHANGE -> waveTable.programChange(channel, data1);
                        case ShortMessage.CONTROL_CHANGE -> waveTable.controlChange(channel, data1, data2);
                        case ShortMessage.PITCH_BEND -> waveTable.pitchBend(channel, data1 | (data2 << 7));
                        default -> {}
                    }
                    // a wave table voice or a stream is no timbre, the wave table plays
                    // it instead of the OPL3, which would sound the wrong patch. a rom
                    // wave is the exception: there is no data for one anywhere, so the
                    // note stays the OPL3's and what it sounds is the timbre of the
                    // patch in the bank, which for an MA-3 preset library is the FM
                    // kit's voice for it, see Vm3SoundbankReader
                    boolean waveTableNote = switch (command) {
                        case ShortMessage.NOTE_ON ->
                                data2 > 0 ? waveTable.noteOn(channel, data1, data2) : waveTable.noteOff(channel, data1);
                        case ShortMessage.NOTE_OFF -> waveTable.noteOff(channel, data1);
                        default -> false;
                    };
                    if (!waveTableNote) {
                        player.midi_write(command, channel, data1, data2);
                    }
                    if (command == ShortMessage.CONTROL_CHANGE && (data1 == 0 || data1 == 32)) {
                        bankSelect(channel, data1, data2);
                    } else if (command == ShortMessage.PROGRAM_CHANGE) {
                        programChange(channel, data1);
                    }
                    if (command == ShortMessage.NOTE_ON) {
logger.log(Level.TRACE, "[%d] ev: %d, ch: %d, p1: %d, p2: %d%s".formatted(timeStamp, command, channel, data1, data2, waveTableNote ? " (wave table)" : ""));
                    }
                }
                case SysexMessage sysexMessage -> {
                    byte[] data = sysexMessage.getData();
                    if ((data[0] & 0xff) == 0x7f) { // Universal Realtime
                        int c = data[1]; // 0x7f: Disregards channel
                        // Sub-ID, Sub-ID2
                        if (data[2] == 0x04 && data[3] == 0x01) { // Device Control / Master Volume
                            float gain = ((data[4] & 0x7f) | ((data[5] & 0x7f) << 7)) / 16383f;
logger.log(Level.DEBUG, "sysex volume: gain: %3.0f".formatted(gain * 127));
                            volume(line, gain);
                        }
                    } else if (!yamahaVoices.process(data)) {
                        // the voices of an MFi or SMAF file are all that is left to take
logger.log(Level.DEBUG, "sysex: %02X\n%s".formatted(sysexMessage.getStatus(), StringUtil.getDump(data, 32)));
                    }
                }
                default -> {}
            }
        }

        @Override
        public void close() {
            isOpen = false;
            receivers.remove(this);
        }

        @Override
        public MidiDevice getMidiDevice() {
            return NukedSynthesizer.this;
        }
    }

    /** the wave table voices registered so far */
    NukedWaveTable getWaveTable() {
        return waveTable;
    }

    /** the voices an MFi or SMAF file has sent */
    YamahaVoices getSmafVoices() {
        return yamahaVoices;
    }

    /**
     * the timbres of the melody voices of a smaf file by (bank LSB, program), which the
     * bank of this synthesizer cannot tell apart, it has no bank select
     */
    private final Map<Integer, NukedPlayer.opl_timbre> smafMelodies = new ConcurrentHashMap<>();

    /** bank select MSB of a channel */
    private final int[] bankMSBs = new int[16];

    /** bank select LSB of a channel */
    private final int[] bankLSBs = new int[16];

    /** remembers the bank of a channel, for {@link #programChange} */
    private void bankSelect(int channel, int control, int value) {
        if (control == 0) {
            bankMSBs[channel] = value;
        } else {
            bankLSBs[channel] = value;
        }
    }

    /** a melody channel of a smaf bank sounds the voice of that bank, not of the program only */
    private void programChange(int channel, int program) {
        if (bankMSBs[channel] == NukedWaveTable.MELODY_BANK) {
            NukedPlayer.opl_timbre timbre = smafMelodies.get((bankLSBs[channel] << 8) | program);
            if (timbre != null) {
                player.midi_program(channel, timbre);
            }
        }
    }

    /**
     * Sounds a voice of a smaf bank as the patch it is for.
     *
     * @see YamahaVoices.Timbres#setVoice(int, int, int, int, VM35FMVoice)
     */
    private void setVoice(int bankMSB, int bankLSB, int program, int drumNote, VM35FMVoice voice) {
        if (bankMSB == NukedWaveTable.MELODY_BANK && drumNote == 0) {
            smafMelodies.put((bankLSB << 8) | program, NukedSoundbank.toTimbre(voice));
        }
        boolean percussion = drumNote != 0;
        setVoice(percussion ? 128 : 0, percussion ? 128 + drumNote : program, voice);
    }

    /**
     * Sounds a voice an MFi or SMAF file sent as the patch it is for.
     *
     * @see YamahaVoices.Timbres
     */
    private void setVoice(int bank, int program, VM35FMVoice voice) {
        boolean percussion = bank == 128;
        NukedInstrument instrument =
                new NukedInstrument(bank, program, percussion, NukedSoundbank.toTimbre(voice));
        soundbank.setInstrument(instrument.getPatch(), instrument);
    }
}
