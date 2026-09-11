/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

import vavi.sound.midi.ymf262.NukedPlayer.opl_timbre;
import vavi.sound.midi.ymf262.NukedSoundbank.NukedInstrument;
import vavi.sound.yamaha.smaf.enums.Enums.VoiceType;
import vavi.sound.yamaha.smaf.enums.Note;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;
import vavi.sound.yamaha.smaf.voice.VM35VoicePC;
import vavi.sound.yamaha.smaf.voice.VMAFMVoice;
import vavi.sound.yamaha.smaf.voice.VMAVoicePC;
import vavi.util.ByteUtil;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.SoundUtil.volume;
import static vavi.sound.midi.MidiUtil.decode87;
import static vavi.sound.midi.ymf262.YmF262MidiDeviceProvider.version;
import static vavi.sound.smaf.message.MachineDependentMessage.SYSEX_PACKED;
import static vavi.sound.yamaha.smaf.voice.VM35Voice.VM35FMVoiceVersion.VM5;


/**
 * NukedSynthesizer.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/03/12 umjammer initial version <br>
 *          0.01 2026-09-11 nsano wave table voices, fix the 8 to 7 bit unpacking <br>
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

    /** wave table (WT) voices, which OPL3 cannot play, see {@link NukedWaveTable} */
    private final NukedWaveTable waveTable = new NukedWaveTable();

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
        return soundbank instanceof NukedSoundbank;
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
                    if (command == ShortMessage.PROGRAM_CHANGE) {
                        waveTable.programChange(channel, data1);
                    }
                    // a wave table voice is no timbre, the adpcm engine plays it
                    // instead of the OPL3, which would sound the wrong patch
                    boolean waveTableNote = switch (command) {
                        case ShortMessage.NOTE_ON ->
                                data2 > 0 ? waveTable.noteOn(channel, data1, data2) : waveTable.claims(channel, data1);
                        case ShortMessage.NOTE_OFF -> waveTable.claims(channel, data1);
                        default -> false;
                    };
                    if (!waveTableNote) {
                        player.midi_write(command, channel, data1, data2);
                    }
                    if (command == ShortMessage.NOTE_ON) {
logger.log(Level.TRACE, "[%d] ev: %d, ch: %d, p1: %d, p2: %d%s".formatted(timeStamp, command, channel, data1, data2, waveTableNote ? " (wave table)" : ""));
                    }
                }
                case SysexMessage sysexMessage -> {
                    byte[] data = sysexMessage.getData();
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
                        case 0x43 -> { // yamaha
                            processYamahaSysexMessage(data);
                        }
                        case 0x45 -> { // vavi
                            if (data[1] == SYSEX_PACKED) { // (f0) 45 7f ... 7f
                                processYamahaSmafSysexMessage(data);
                            }
                        }
                        default -> {
logger.log(Level.DEBUG, "sysex: %02X\n%s".formatted(sysexMessage.getStatus(), StringUtil.getDump(data, 32)));
                        }
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

    /**
     *
     * <li>[MA-3] stream PCM pair
     * <p>
     * You can set two specified stream PCMs to sound synchronously.
     * After receiving the sync message, any note-on will cause the two sounds to be played simultaneously.
     * </p>
     * <pre>
     * [SMAF]
     * ex. F0 xx 43 79 06 7F 08 cl id1 id2 F7
     *  　　cl=00(synchronize),01(cancel)
     *    　id1=00 ~ 20(Wave ID 1)
     *    　id2=00 ~ 20(Wave ID 2)
     * </pre>
     * <li> MA-3/MA-5 stream PCM wave pan pot
     * <p>
     * Sets the stereo location position of the specified stream PCM wave.
     * </p>
     * <pre>
     * ex. F0 xx 43 79 06 7F 0B id pp dd F7
     *  　　id=00 ~ 20(Wave ID)
     *  　　pp=00(specify),01(clear),02(off)
     *  　　dd=00 ~ 7F(localization: Center=40)
     * </pre>
     * Once this is specified, the channel panpot (CC#10) specification will have no effect unless cleared.
     * <pre>
     * ----------------------
     *  MA-3 master volume
     *  MA-3 stream PCM pair
     *  MA-3 stream PCM wave, pan pot
     *  MA-3 interruption setting
     *  ----------------------
     * </pre>
     * <pre>
     *
     * [XF cue point] (04)
     *          43 7B 02 rr
     *
     * [specify channel status] (14)
     *          43 02 00 04 dd ... dd
     *
     * [MA-5 AL specify channel] (06)
     *          43 02 01 01 cc dd
     *
     * [MA-5 V specify voice channel] (06)
     *          43 02 01 02 cc dd
     *
     * [???] (puc)
     *          43 01 80 31 xx F7
     *                      ~~ tempo data?　set by Mtsu
     *
     * [???] (my dump)
     *          43 03 91 18 00 F7
     *          43 03 91 18 00 F7
     *          43 03 91 19 10 F7
     *          43 03 91 1A 32 F7
     *          43 03 91 1C 76 F7
     *          43 03 91 1D 98 F7
     *
     * [???] (puc) (05)
     * FF F0 05 43 02 80 ** F7
     *                   ~~ msec seems per 1 delta time
     *
     * [voice setting] (puc) (13)
     *          43 02 01 00 50 72 9B 3F C1 98 4B 3F C0 00 10 21 42 00 F7
     *                   ~~ ~~  1st byte is 00, 2nd byte is voice number
     *
     * [FMAll4HPS] (mmftool)
     *          43 03 00 00 47 50 01 25 1B 92 42 A0 14 72 71 00 A0 F7
     *                ~~ ~~ 1: no, 2: 00 or 0x80
     *
     * [MA-3 SetVoiceFM(0x1f,0x2f)/MA-3 SetVoiceWT(0x1e)] (mmftool)
     *          43 79 06 7F 01 xx tt nn
     *
     * [MA-5 SetVoiceFM(0x1c,0x2a)/MA-5 SetVoiceWT(0x1b)] (mmftool)
     *          43 79 07 7F 01
     *
     * [Reset] (mmftool)
     *          43 79    7F 7F
     *
     * [Volume] (mmftool)
     *          43 79    7F 00
     *
     * [???] (mmftool)
     *          43 79    7F 07
     *
     * [MA-3,5 SetWave] (mmftool)
     *          43 79    7F 03
     *
     * [stream PCM wave pan-pot] (proper)
     *          43 79 06 7F 0B ii cc dd F7
     *             ii: WaveID 1 ~ 32 （1H ~ 20F）
     *             cc: specify pan-pot 0,clear 1, pan off 2
     *             dd: pan-pot value 0 ~ 127 (00H ~ 7FH)
     *
     * [user event] (proper)
     *         43 79 06 7F 10 dd F7
     *             dd: user event type 0 ~ 15 (0H ~ FH)
     *
     * </pre>
     *
     * @param data 45 7f packed 7bit data ... 7f
     * @see "https://web.archive.org/web/20050210122232/http://www.music.ne.jp/~puc/mmf_format.html"
     * @see "ATS-MA5-SMAF_GL_133_HV.pdf"
     * @see "https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool"
     * @see "https://github.com/but80/smaf825/blob/v1/smaf/subtypes/exclusive.go#L85C1-L160C3"
     * @see "http://khhl0fx.web.fc2.com/melo/neiro.html"
     */
    void processYamahaSmafSysexMessage(byte[] data) {
        // (f0) 45 7f {encoded ...} f7, the packer encodes the whole exclusive
        // including its own trailing 0xf7 and then repeats that 0xf7 raw, so every
        // encoded byte is data[2] ... data[length - 2] and the decoded exclusive
        // already ends with 0xf7. Cutting one byte short here loses the last
        // block's high bit flags, which shows up as stray 0x80s in the tail of a
        // voice.
        byte[] encoded = Arrays.copyOfRange(data, 2, data.length - 1);
        byte[] decoded = new byte[((encoded.length + 1) * 7) / 8]; // for 8bits data
        int n = decode87(encoded, decoded, 0, encoded.length);
        byte[] sysex = Arrays.copyOf(decoded, n);

        logger.log(Level.DEBUG, "smaf sysex: YAMAHA <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n%s".formatted(StringUtil.getDump(sysex, 32)));

        try {
            switch (sysex[1] & 0xff) {
                case 0x79 -> {
                    if (sysex.length >= 10 && (sysex[2] & 0xff) == 0x07 && (sysex[3] & 0xff) == 0x7f && (sysex[4] & 0xff) == 0x01) {
                        //
                        // [VM5] (smaf825)
                        //         10 <= len
                        //         43 79 07 7F 01 mm ll pc dn vt ...
                        //             mm: BankMSB
                        //             ll: BankLSB
                        //             pc: PC
                        //             dn: DrumNote
                        //             vt: VoiceType
                        //             vv: version
                        //
                        VoiceType voiceType = voiceType(sysex);
                        if (voiceType == VoiceType.FM) {
                            VM35VoicePC x = new VM35VoicePC();
                            x.version = VM5;
                            x.bankMSB = sysex[5] & 0xff;
                            x.bankLSB = sysex[6] & 0xff;
                            x.pc = sysex[7] & 0xff;
                            x.drumNote = new Note(sysex[8] & 0xff);
                            x.voice = new VM35FMVoice(voiceImage(sysex), VM5);
                            registerVoice(x);
                        } else if (voiceType == VoiceType.PCM) {
                            // a wave table voice, OPL3 has no sample path so the
                            // adpcm engine plays it, see NukedWaveTable
                            waveTable.setVoice(sysex[7] & 0xff, sysex[8] & 0xff, voiceImage(sysex));
                        }
                    } else if (sysex.length >= 10 && (sysex[2] & 0xff) == 0x06 && (sysex[3] & 0xff) == 0x7f && (sysex[4] & 0xff) == 0x01) {
                        //
                        // [VM3Exclusive] (smaf825)
                        //         10 <= len
                        //         43 79 06 7F 01 mm ll pc dn vt ...
                        //             mm: BankMSB
                        //             ll: BankLSB
                        //             pc: PC
                        //             dn: DrumNote
                        //             vt: VoiceType
                        //             vv: version
                        //
                        VoiceType voiceType = voiceType(sysex);
                        if (voiceType == VoiceType.FM) {
                            VM35VoicePC x = new VM35VoicePC();
                            x.version = VM5;
                            x.bankMSB = sysex[5] & 0xff;
                            x.bankLSB = sysex[6] & 0xff;
                            x.pc = sysex[7] & 0xff;
                            x.drumNote = new Note(sysex[8] & 0xff);
                            x.voice = new VM35FMVoice(voiceImage(sysex), VM5);
                            registerVoice(x);
                        } else if (voiceType == VoiceType.PCM) {
                            waveTable.setVoice(sysex[7] & 0xff, sysex[8] & 0xff, voiceImage(sysex));
                        }
                    }
                }
                case 0x05 -> {
                    if (sysex.length > 5 && (sysex[2] & 0xff) == 0x00) {
                        //
                        // [EXWV] the wave a wave table voice plays
                        //         5 < len
                        //         43 05 00 ii <4 bit adpcm ...> f7
                        //             ii: wave id, what the "RM, WaveID" byte of a voice refers to
                        //
                        waveTable.setWave(sysex[3] & 0xff, Arrays.copyOfRange(sysex, 4, sysex.length - 1));
                    } else if (sysex.length >= 22 && (sysex[2] & 0xff) == 0x02) {
                        //
                        // [EXVO] a SMAF wave table voice (smaf825)
                        //         22 <= len
                        //         43 05 02 bb pp <16 byte VM35 PCM voice> f7
                        //             bb: bank, bit 7 marks a drum bank
                        //             pp: program
                        //
                        // its wave is the "EXWV" next to it, which vavi-sound puts
                        // straight into the smaf wave engine, see NukedWaveTable
                        //
                        waveTable.setSmafVoice(sysex[3] & 0xff, sysex[4] & 0xff,
                                Arrays.copyOfRange(sysex, 5, sysex.length - 1));
                    } else if (sysex.length >= 3 && (sysex[2] & 0xff) == 0x01) {
                        //
                        // [VM5] (smaf825)
                        //         3 <= len
                        //         43 05 01 ll pc ...
                        //             ll: BankLSB
                        //             pc: PC
                        //
                        VM35VoicePC x = new VM35VoicePC();
                        x.version = VM5;
                        x.bankMSB = 0;
                        x.bankLSB = sysex[3] & 0xff;
                        x.pc = sysex[4] & 0xff;
                        x.drumNote = new Note(0);
                        x.voice = new VM35FMVoice(Arrays.copyOfRange(sysex, 5, sysex.length), VM5);
                        registerVoice(x);
                    }
                }
                case 0x03 -> {
                    if (sysex.length == VMA_VOICE_2OP || sysex.length == VMA_VOICE_4OP) {
                        //
                        // [VoicePC] (smaf825) a VMA (MA-1 / MA-2) voice
                        //         len 18 (2 operator) or 28 (4 operator)
                        //         43 03 nn ll pc <2 + 5 * operators byte VMA FM voice> f7
                        //             nn: voice index inside the file, 0 ~ 15
                        //             ll: BankLSB
                        //             pc: PC
                        //
                        // nn is an index, not a flag, so the length is what tells a
                        // voice from the 6 byte "43 03 90 / 91" messages. Checked
                        // over 1708 smaf files: 1805 voices are 18 bytes and 134 are
                        // 28, and no message of any other length starts with 43 03
                        // except those 6 byte ones.
                        //
                        VMAVoicePC x = new VMAVoicePC();
                        x.bank = sysex[3] & 0xff;
                        x.pc = sysex[4] & 0xff;
                        x.voice = vmaFmVoice(Arrays.copyOfRange(sysex, 5, sysex.length - 1));
                        VM35VoicePC converted = x.voice != null ? toVM35(x) : null;
                        if (converted != null) {
                            registerVoice(converted);
                        }
                    }
                }
                default -> {
                    logger.log(Level.DEBUG, "smaf sysex: YAMAHA unhandled");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The {@code vt} byte of a voice exclusive.
     * <p>
     * It names no type at all in 111 of the 43326 exclusives of a 1845 file smaf
     * corpus, and indexing the enum with it blindly throws.
     * </p>
     *
     * @return null when it is none of {@link VoiceType}
     */
    private static VoiceType voiceType(byte[] sysex) {
        int vt = sysex[9] & 0xff;
        if (vt >= VoiceType.values().length) {
logger.log(Level.DEBUG, "unknown voice type, ignored: %02x".formatted(vt));
            return null;
        }
        return VoiceType.values()[vt];
    }

    /**
     * A VMA voice as a VM35 one.
     * <p>
     * Up to {@code vavi-sound-ma} 0.0.2 this never succeeds: {@code VMAFMVoice#ToVM35}
     * fills its operator list with {@code List#set} on an {@code ArrayList} of size
     * 0 - {@code new ArrayList<>(4)} is a capacity, not a size, unlike the go
     * {@code make([]T, 4)} it is a port of - so it throws
     * {@link IndexOutOfBoundsException} and the voice never arrives. Fixed in
     * 0.0.3-SNAPSHOT; the catch stays until that is the version this builds
     * against, and a voice cannot be built by hand from here instead - the
     * {@code VMAFMVoice} fields it would take are package private.
     * </p>
     *
     * @return null when the conversion fails
     */
    private VM35VoicePC toVM35(VMAVoicePC x) {
        try {
            return x.toVM35();
        } catch (RuntimeException e) {
            warnEmptyVoice(e.toString());
            return null;
        }
    }

    /** the note above, logged once rather than once per voice */
    private void warnEmptyVoice(String why) {
        if (!warnedEmptyVoice) {
            warnedEmptyVoice = true;
logger.log(Level.WARNING, "a VMA voice cannot be converted, not registered (" + why + "). vavi-sound-ma 0.0.3 or later is needed, up to 0.0.2 VMAFMVoice#ToVM35 sets into an empty operator list instead of adding to it");
        }
    }

    /** the operators {@link #convertToOplTimbre} needs */
    private static final int OPERATORS = 2;

    /** a VMA voice exclusive holding a 2 operator voice */
    private static final int VMA_VOICE_2OP = 18;

    /** a VMA voice exclusive holding a 4 operator voice */
    private static final int VMA_VOICE_4OP = 28;

    /**
     * The 2 global bytes and 5 bytes per operator a VMA FM voice is.
     * <p>
     * {@code new VMAFMVoice(byte[])} of {@code vavi-sound-ma} 0.0.2 cannot be used:
     * it calls {@code readUnusedRest} without {@code read} first, so its
     * {@code alg} is still null and it throws. Fixed in 0.0.3-SNAPSHOT, reading it
     * here keeps this working against either.
     * </p>
     *
     * @return null when the image does not read
     */
    private static VMAFMVoice vmaFmVoice(byte[] image) {
        VMAFMVoice voice = new VMAFMVoice();
        int[] rest = {image.length};
        DataInputStream rdr = new DataInputStream(new ByteArrayInputStream(image));
        try {
            voice.read(rdr, rest);
            if (rest[0] > 0) {
                voice.readUnusedRest(rdr, rest); // the operators ALG does not use
            }
            return voice;
        } catch (IOException | RuntimeException e) {
logger.log(Level.WARNING, "VMA voice does not read, " + image.length + " bytes: " + e);
            return null;
        }
    }

    /** the wave table voices registered so far */
    NukedWaveTable getWaveTable() {
        return waveTable;
    }

    /**
     * The voice image of a {@code 43 79 0x 7f 01} exclusive, that is everything
     * after the {@code vt} byte and before the trailing {@code 0xf7}.
     */
    private static byte[] voiceImage(byte[] sysex) {
        return Arrays.copyOfRange(sysex, 10, sysex.length - 1);
    }

    /** so the note below is logged once, not once per voice */
    private boolean warnedEmptyVoice;

    private void registerVoice(VM35VoicePC x) {
        if (x.voice instanceof VM35FMVoice fmVoice) {
            if (fmVoice.operators == null || fmVoice.operators.size() < OPERATORS) {
                // a voice which did not parse, see toVM35
                warnEmptyVoice("no operator");
                return;
            }
            opl_timbre timbre = convertToOplTimbre(fmVoice);
            boolean percussion = x.isForDrum();
            int bank = percussion ? 128 : 0;
            int program = percussion ? (128 + (x.drumNote != null ? x.drumNote.note : 0)) : x.pc;
            NukedInstrument instrument = new NukedInstrument(bank, program, percussion, timbre);
            soundbank.setInstrument(instrument.getPatch(), instrument);
        }
    }

    private static opl_timbre convertToOplTimbre(VM35FMVoice voice) {
        int[] seed = new int[13];
        var op0 = voice.operators.get(0);
        var op1 = voice.operators.get(1);

        seed[0] = (op0.eam ? 0x80 : 0) | (op0.evb ? 0x40 : 0) | (op0.sus ? 0x20 : 0) | (op0.ksr ? 0x10 : 0) | (op0.multi.ordinal() & 0x0f);
        seed[1] = (op1.eam ? 0x80 : 0) | (op1.evb ? 0x40 : 0) | (op1.sus ? 0x20 : 0) | (op1.ksr ? 0x10 : 0) | (op1.multi.ordinal() & 0x0f);

        seed[2] = (op0.ksl << 6) | (op0.tl & 0x3f);
        seed[3] = (op1.ksl << 6) | (op1.tl & 0x3f);

        seed[4] = (op0.ar << 4) | (op0.dr & 0x0f);
        seed[5] = (op1.ar << 4) | (op1.dr & 0x0f);

        seed[6] = (op0.sl << 4) | (op0.rr & 0x0f);
        seed[7] = (op1.sl << 4) | (op1.rr & 0x0f);

        seed[8] = op0.ws & 0x07;
        seed[9] = op1.ws & 0x07;

        int fbVal = op0.fb & 0x07;
        int algVal = voice.alg.ordinal() & 0x01;
        seed[10] = 0x30 | (fbVal << 1) | algVal;

        seed[11] = 0;
        seed[12] = 4;

        return new opl_timbre(seed);
    }

    /** */
    void processYamahaSysexMessage(byte[] data) {
        logger.log(Level.DEBUG, "midi sysex: YAMAHA <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n%s".formatted(StringUtil.getDump(data, 32)));
        logger.log(Level.DEBUG, "midi sysex: YAMAHA unhandled");
    }
}
