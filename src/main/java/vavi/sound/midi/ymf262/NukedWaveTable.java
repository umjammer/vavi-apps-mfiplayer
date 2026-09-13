/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vavi.sound.adpcm.ma.MaInputStream;
import vavi.sound.mobile.AudioEngine;
import vavi.sound.mobile.StreamExclusive;
import vavi.sound.smaf.vavi.sequencer.WaveSequencer;

import static java.lang.System.getLogger;


/**
 * The MA-3 / MA-5 wave table (WT) voices and stream PCM of an OPL3 synthesizer.
 * <p>
 * OPL3 is FM only, so a WT voice cannot become an
 * {@link vavi.sound.midi.ymf262.NukedPlayer.opl_timbre} the way an FM voice does. This is
 * the sampler part of the MA chip instead, whose output a synthesizer mixes into the OPL3
 * one ({@link #render}): a WT voice is a 4 bit ADPCM wave in the wave ram plus the 16 byte
 * voice which says how to play it, and a stream is a wave a note starts and stops as it is.
 * </p>
 * <h2>voice</h2>
 * <pre>
 *      | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
 *  + 0 |             Fs(H)             |  the rate the wave sounds key 60 at
 *  + 1 |             Fs(L)             |
 *  + 2 |      panpot       |   ?   |P E|
 *  + 3 |  lfo  |           ?           |
 *  + 4 |      S R      |xof|   |sus|   |
 *  + 5 |      R R      |      D R      |
 *  + 6 |      A R      |      S L      |
 *  + 7 |          T L          |   ?   |
 *  + 8 | ? |  dam  |eam| ? |  dvb  |evb|
 *  + 9 |       (wave address)          |  the driver writes it, a file has 0
 *  +10 |                               |
 *  +11 |             LP(H)             |  loop point [sample]
 *  +12 |             LP(L)             |
 *  +13 |             EP(H)             |  end point [sample]
 *  +14 |             EP(L)             |
 *  +15 |R M|         ...WaveID         |  RM = 1: a preset (rom) wave, see {@link MaRomWaves}
 * </pre>
 * <p>
 * Which patch a voice is follows {@code Set_Voice3} / {@code Bank_Program3} of the MA-3
 * driver ({@code mammfcnv.c} of MA-3-MegaMod): a channel whose bank select MSB is
 * {@code 0x7c} plays the melody voice of (bank LSB, program), one whose MSB is
 * {@code 0x7d} the drum voice of (program, note), and a channel without either - an MFi
 * file, a plain midi file - the voice of its program, channel 9 being the drum one.
 * </p>
 * <p>
 * The pitch follows {@code GetWtBlockFnum} of {@code masnddrv.c}: a melody voice sounds
 * {@code Fs * 2^((key - 60) / 12)}, never faster than 48kHz, a drum voice always key 60.
 * The envelope is AR, DR to SL, SR, and RR after the key off - which XOF ignores - on the
 * OPL rate curve, which is what the chip's FM and WT slots share; LFO, DAM and DVB are not
 * modelled, nor are the reduced key follow tables of melody programs 115 ~ 127.
 * </p>
 * <h2>stream</h2>
 * <p>
 * A note of key 0 ~ 12 or 92 ~ 110 on a drum channel starts stream {@code key + 1} or
 * {@code key - 78} (the "Mwa*" chunk number) and its gate stops it, see {@code Note_ON3}.
 * An MFi audio message or a SMAF PCM audio track says start and stop itself,
 * see {@link StreamExclusive}.
 * </p>
 * <h2>preset (rom) wave</h2>
 * <p>
 * A voice whose {@code RM} bit is set plays wave 0 ~ 6 of the chip's rom, which is not here
 * unless {@link MaRomWaves#ROM_KEY} names a file that has it. Without it a voice of one is
 * not claimed and the OPL3 plays the note with whatever timbre the patch has.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-11 nsano initial version <br>
 *          0.01 2026-09-13 nsano a sampler of its own instead of the adpcm engine: pitch,
 *                                loop, envelope, bank, stream pcm <br>
 *          0.02 2026-09-13 nsano preset (rom) waves <br>
 * @see "https://github.com/but80/smaf825/blob/v1/smaf/voice/vm35_pcm_voice.go"
 * @see "MA-3-MegaMod/megagrrl_ymu762 code/firmware/main/YMU762/mammfcnv.c"
 */
class NukedWaveTable {

    private static final Logger logger = getLogger(NukedWaveTable.class.getName());

    /** general midi rhythm channel, a drum channel when there is no smaf bank select */
    private static final int DRUM_CHANNEL = 9;

    /** bank select MSB of a smaf melody channel */
    static final int MELODY_BANK = 0x7c;

    /** bank select MSB of a smaf drum channel */
    static final int DRUM_BANK = 0x7d;

    /** the bank of a voice which came without a smaf bank, an MFi one */
    private static final int ANY = 0x80;

    /** how many waves sound at once, the MA-5 has 32 slots for FM, WT and stream together */
    private static final int MAX_PLAYERS = 32;

    /** the fastest a wave table slot plays [Hz] */
    private static final int MAX_FS = 48000;

    /** the key a voice sounds {@link Voice#samplingRate} at */
    private static final int BASE_KEY = 60;

    /** the level of a full scale wave against the OPL3 output */
    private static final double GAIN = Double.parseDouble(System.getProperty("vavi.sound.midi.ymf262.waveTable.gain", "0.5"));

    /** the wave format a SMAF "EXWV" is stored under in the smaf wave engine, see {@code WaveType} */
    private static final int SMAF_ADPCM = 1;

    /** one registered wave table voice, the 16 byte VM35 PCM voice image */
    static class Voice {

        /** 1 ~ 48000 [Hz], at key 60 */
        final int samplingRate;
        /** 0 ~ 31 */
        final int panpot;
        /** true: {@link #panpot} rather than the channel's */
        final boolean panEnable;
        final int sr;
        /** true: the key off is ignored */
        final boolean xof;
        final boolean sus;
        final int rr;
        final int dr;
        final int ar;
        final int sl;
        /** 0 ~ 63, 0.75dB a step */
        final int tl;
        /** [sample] */
        final int loopPoint;
        /** [sample] */
        final int endPoint;
        /** true: a preset (rom) wave, see {@link MaRomWaves} */
        final boolean romWave;
        /** the wave this voice plays, a rom one when {@link #romWave} is true */
        final int waveId;
        /** a SMAF "EXVO" one, whose wave may be in the SMAF wave engine instead */
        final boolean smaf;

        Voice(byte[] image) {
            this(image, false);
        }

        Voice(byte[] image, boolean smaf) {
            this.smaf = smaf;
            this.samplingRate = ((image[0] & 0xff) << 8) | (image[1] & 0xff);
            this.panpot    = (image[2] >> 3) & 0x1f;
            this.panEnable = (image[2] & 0x01) != 0;
            this.sr        = (image[4] >> 4) & 0x0f;
            this.xof       = (image[4] & 0x08) != 0;
            this.sus       = (image[4] & 0x02) != 0;
            this.rr        = (image[5] >> 4) & 0x0f;
            this.dr        =  image[5] & 0x0f;
            this.ar        = (image[6] >> 4) & 0x0f;
            this.sl        =  image[6] & 0x0f;
            this.tl        = (image[7] >> 2) & 0x3f;
            this.loopPoint = ((image[11] & 0xff) << 8) | (image[12] & 0xff);
            this.endPoint  = ((image[13] & 0xff) << 8) | (image[14] & 0xff);
            this.romWave   =  (image[15] & 0x80) != 0;
            this.waveId    =   image[15] & 0x7f;
        }

        @Override
        public String toString() {
            return samplingRate + "Hz, lp: " + loopPoint + ", ep: " + endPoint +
                    ", ar: " + ar + ", dr: " + dr + ", sl: " + sl + ", sr: " + sr + ", rr: " + rr + ", tl: " + tl +
                    (xof ? ", xof" : "") + (sus ? ", sus" : "") + (panEnable ? ", pan: " + panpot : "") +
                    ", " + (romWave ? "rom wave " : "wave ") + waveId + (smaf ? " (smaf)" : "");
        }
    }

    /** a wave in the wave ram */
    private static class Wave {
        /** 4 bit adpcm */
        final byte[] adpcm;
        /** decoded when it is first played */
        short[] pcm;

        Wave(byte[] adpcm) {
            this.adpcm = adpcm;
        }

        short[] pcm() {
            if (pcm == null) {
                pcm = decodeAdpcm(adpcm, 0, adpcm.length);
            }
            return pcm;
        }
    }

    /** a stream wave */
    private static class Stream {
        /** mono: one, stereo: left and right */
        final short[][] pcm;
        final int samplingRate;
        /** 0 ~ 127, -1: the channel's, see {@code 43 79 0x 7f 0b} */
        int panpot = -1;
        /** the stream which starts with this one, 0: none, see {@code 43 79 0x 7f 08} */
        int pair;

        Stream(short[][] pcm, int samplingRate) {
            this.pcm = pcm;
            this.samplingRate = samplingRate;
        }
    }

    /** what a midi channel has been told */
    private static class Channel {
        int bankMSB = -1;
        int bankLSB;
        int program;
        int volume = 100;
        int expression = 127;
        int pan = 64;
        /** -8192 ~ 8191 */
        int bend;
        /** [semitone] */
        int bendRange = 2;
        boolean sustain;
        /** registered parameter number, 0x3fff: none */
        int rpn = 0x3fff;
    }

    /** wave id -> wave */
    private final Map<Integer, Wave> waves = new HashMap<>();

    /** the 4 bit adpcm of the rom waves {@link MaRomWaves#ROM_KEY} names, read once */
    private static final class Rom {
        static final byte[][] WAVES = MaRomWaves.load();
    }

    /** rom wave id -> wave */
    private final Map<Integer, Wave> romWaves = new HashMap<>();

    /** (bank LSB or {@link #ANY}, program) -> voice */
    private final Map<Integer, Voice> melodies = new HashMap<>();

    /** (program or {@link #ANY}, note) -> voice */
    private final Map<Integer, Voice> drums = new HashMap<>();

    /** stream id -> stream */
    private final Map<Integer, Stream> streams = new HashMap<>();

    private final Channel[] channels = new Channel[16];

    /** volume of an MFi audio channel 0 ~ 3, 0 ~ 127 */
    private final int[] audioVolumes = {127, 127, 127, 127};

    /** panpot of an MFi audio channel 0 ~ 3, 0 ~ 127 */
    private final int[] audioPanpots = {64, 64, 64, 64};

    /** what sounds now */
    private final List<Player> players = new ArrayList<>();

    /** (channel, note) a note on of which this has taken, for the note off to be taken too */
    private final Set<Integer> heldNotes = new HashSet<>();

    /** the rate {@link #render} writes at */
    private final int sampleRate;

    NukedWaveTable() {
        this(44100);
    }

    /** @param sampleRate the rate {@link #render} writes at */
    NukedWaveTable(int sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel();
        }
        if (Rom.WAVES != null) {
            for (int i = 0; i < Rom.WAVES.length; i++) {
                romWaves.put(i, new Wave(Rom.WAVES[i]));
            }
        }
    }

    private static int key(int a, int b) {
        return (a << 8) | b;
    }

    // ---- registration

    /**
     * Registers a wave table voice of a {@code 43 79 0x 7f 01} exclusive.
     *
     * @param bankMSB {@link #MELODY_BANK}, {@link #DRUM_BANK}, or anything else for a voice without a smaf bank
     * @param bankLSB the bank a melody voice is in
     * @param program the program of a melody voice, the drum kit of a drum one
     * @param drumNote the note of a drum voice, 0 for a melody voice
     * @param image the 16 byte VM35 PCM voice
     */
    synchronized void setVoice(int bankMSB, int bankLSB, int program, int drumNote, byte[] image) {
        if (image.length < 16) {
logger.log(Level.WARNING, "wave table voice is too short: " + image.length);
            return;
        }
        Voice voice = new Voice(image);
        boolean drum = bankMSB == DRUM_BANK || (bankMSB != MELODY_BANK && drumNote != 0);
        if (drum) {
            drums.put(key(bankMSB == DRUM_BANK && bankLSB == 0 ? program : ANY, drumNote), voice);
        } else {
            melodies.put(key(bankMSB == MELODY_BANK ? bankLSB : ANY, program), voice);
        }
logger.log(Level.DEBUG, "wave table voice: bank: %02x/%02x, program: %d".formatted(bankMSB, bankLSB, program) +
        (drum ? ", drum note: " + drumNote : "") + ", " + voice);
    }

    /**
     * Registers a SMAF "EXVO" wave table voice, whose wave is an "EXWV" one.
     * <pre>
     *  43 05 02 bb pp &lt;16 byte VM35 PCM voice&gt; f7
     *           ~~ ~~
     *           |  +--- program
     *           +------ bank, bit 7 marks a drum (rhythm) bank
     * </pre>
     * <p>
     * There is no drum note byte here the way {@code 43 79 07 7f 01} has one, so a
     * drum bank is read as "the program is the note", which is how SMAF addresses a
     * drum. <b>TODO</b> unverified - the whole 1845 file corpus holds one single
     * {@code 43 05 02} and it is a melody voice.
     * </p>
     *
     * @param bank the {@code bb} byte, bit 7 marks a drum bank
     * @param program the {@code pp} byte
     * @param image the 16 byte VM35 PCM voice
     * @see vavi.sound.smaf.vavi.chunk.ExclusiveVoiceChunk
     */
    synchronized void setSmafVoice(int bank, int program, byte[] image) {
        if (image.length < 16) {
logger.log(Level.WARNING, "smaf wave table voice is too short: " + image.length);
            return;
        }
        Voice voice = new Voice(image, true);
        if ((bank & 0x80) != 0) {
            drums.put(key(ANY, program), voice);
        } else {
            melodies.put(key(bank & 0x7f, program), voice);
        }
logger.log(Level.DEBUG, "smaf wave table voice: bank: %02x, program: %d, ".formatted(bank, program) + voice);
    }

    /**
     * Registers the wave a voice plays.
     *
     * @param waveId what the {@code RM, WaveID} byte of a voice refers to
     * @param adpcm 4 bit adpcm
     */
    synchronized void setWave(int waveId, byte[] adpcm) {
        waves.put(waveId, new Wave(adpcm));
logger.log(Level.DEBUG, "wave table wave: No." + waveId + ", " + adpcm.length + " bytes adpcm");
    }

    /**
     * Registers a preset (rom) wave, what a voice whose {@code RM} bit is set refers to.
     *
     * @param waveId 0 ~ 6
     * @param adpcm 4 bit adpcm
     * @see MaRomWaves
     */
    synchronized void setRomWave(int waveId, byte[] adpcm) {
        romWaves.put(waveId, new Wave(adpcm));
    }

    /** Forgets a wave, {@code 43 79 0x 7f 04}. */
    synchronized void removeWave(int waveId) {
        waves.remove(waveId);
    }

    /** the wave registered for an id, null when there is none */
    synchronized byte[] getWave(int waveId) {
        Wave wave = waves.get(waveId);
        return wave != null ? wave.adpcm : null;
    }

    /**
     * Registers a stream wave.
     *
     * @param id 1 ~
     * @param channels 1, 2
     * @param bits 4 for adpcm, 8 or 16 for pcm
     * @param data a stereo adpcm wave is L then R, a stereo pcm one interleaved
     */
    synchronized void setStream(int id, StreamExclusive.Format format, int channels, int bits, int samplingRate, byte[] data) {
        if (samplingRate < 1 || samplingRate > MAX_FS || channels < 1 || channels > 2) {
logger.log(Level.WARNING, "stream wave not supported: No.%d, %dHz, %d ch".formatted(id, samplingRate, channels));
            return;
        }
        short[][] pcm = new short[channels][];
        switch (format) {
            case ADPCM -> {
                int half = data.length / channels;
                for (int c = 0; c < channels; c++) {
                    pcm[c] = decodeAdpcm(data, half * c, half);
                }
            }
            case SIGNED, UNSIGNED -> {
                int bytes = bits > 8 ? 2 : 1;
                int frames = data.length / bytes / channels;
                for (int c = 0; c < channels; c++) {
                    pcm[c] = new short[frames];
                }
                for (int i = 0; i < frames; i++) {
                    for (int c = 0; c < channels; c++) {
                        int p = (i * channels + c) * bytes;
                        int sample = bytes == 2 ? ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff) : (data[p] & 0xff) << 8;
                        if (format == StreamExclusive.Format.UNSIGNED) {
                            sample ^= 0x8000; // offset binary to 2's complement
                        }
                        pcm[c][i] = (short) sample;
                    }
                }
            }
        }
        Stream old = streams.put(id, new Stream(pcm, samplingRate));
        if (old != null) {
            streams.get(id).panpot = old.panpot;
            streams.get(id).pair = old.pair;
        }
logger.log(Level.DEBUG, "stream wave: No.%d, %s, %dHz, %d ch, %d samples".formatted(id, format, samplingRate, channels, pcm[0].length));
    }

    /**
     * The stream panpot, {@code 43 79 0x 7f 0b id pp dd}.
     *
     * @param mode 0: dd, 1: clear, 2: off (center)
     */
    synchronized void setStreamPanpot(int id, int mode, int panpot) {
        Stream stream = streams.get(id);
        if (stream == null) {
logger.log(Level.DEBUG, "stream panpot for no stream: " + id);
            return;
        }
        stream.panpot = switch (mode) {
            case 0 -> panpot & 0x7f;
            case 2 -> 64;
            default -> -1;
        };
    }

    /**
     * The stream pair, {@code 43 79 0x 7f 08 cl id1 id2}: a note of either starts both.
     *
     * @param cancel 0: pair, else: cancel
     */
    synchronized void setStreamPair(boolean cancel, int id1, int id2) {
        Stream stream1 = streams.get(id1);
        Stream stream2 = streams.get(id2);
        if (stream1 == null || stream2 == null || id1 == id2) {
logger.log(Level.DEBUG, "stream pair for no stream: " + id1 + ", " + id2);
            return;
        }
        stream1.pair = cancel ? 0 : id2;
        stream2.pair = cancel ? 0 : id1;
    }

    /** the volume of an MFi audio channel, 0 ~ 127 */
    synchronized void setAudioVolume(int channel, int volume) {
        if (channel >= 0 && channel < audioVolumes.length) {
            audioVolumes[channel] = volume;
        }
    }

    /** the panpot of an MFi audio channel, 0 ~ 127 */
    synchronized void setAudioPanpot(int channel, int panpot) {
        if (channel >= 0 && channel < audioPanpots.length) {
            audioPanpots[channel] = panpot;
        }
    }

    // ---- midi

    /** Remembers what patch a channel plays. */
    synchronized void programChange(int channel, int program) {
        if (channel >= 0 && channel < channels.length) {
            channels[channel].program = program;
        }
    }

    /** Remembers the bank, the levels, the pan and the bend range of a channel. */
    synchronized void controlChange(int channel, int control, int value) {
        if (channel < 0 || channel >= channels.length) {
            return;
        }
        Channel c = channels[channel];
        switch (control) {
            case 0 -> c.bankMSB = value;
            case 32 -> c.bankLSB = value;
            case 7 -> c.volume = value;
            case 10 -> c.pan = value;
            case 11 -> c.expression = value;
            case 64 -> {
                c.sustain = value >= 64;
                if (!c.sustain) {
                    players.stream().filter(p -> p.channel == channel && p.sustained).forEach(p -> {
                        p.sustained = false;
                        p.keyOff();
                    });
                }
            }
            case 100 -> c.rpn = (c.rpn & 0x3f80) | value;
            case 101 -> c.rpn = (c.rpn & 0x7f) | (value << 7);
            case 6 -> {
                if (c.rpn == 0) c.bendRange = value;
            }
            case 120, 123 -> players.removeIf(p -> p.channel == channel);
            case 121 -> {
                c.volume = 100; c.expression = 127; c.bend = 0; c.sustain = false; c.rpn = 0x3fff;
            }
            default -> {}
        }
    }

    /** @param value 0 ~ 16383 */
    synchronized void pitchBend(int channel, int value) {
        if (channel >= 0 && channel < channels.length) {
            channels[channel].bend = value - 8192;
        }
    }

    private boolean isDrum(int channel) {
        int bankMSB = channels[channel].bankMSB;
        return bankMSB == DRUM_BANK || (bankMSB != MELODY_BANK && channel == DRUM_CHANNEL);
    }

    /** the stream a note starts, 0 when it is none */
    private int streamId(int channel, int note) {
        if (!isDrum(channel)) {
            return 0;
        }
        int id = note <= 12 ? note + 1 : note >= 92 ? note - 78 : 0;
        return streams.containsKey(id) ? id : 0;
    }

    /** the voice a note plays, null when there is none */
    private Voice voice(int channel, int note) {
        Channel c = channels[channel];
        Voice voice;
        if (isDrum(channel)) {
            voice = c.bankMSB == DRUM_BANK && c.bankLSB == 0 ? drums.get(key(c.program, note)) : null;
            if (voice == null) {
                voice = drums.get(key(ANY, note));
            }
        } else {
            voice = c.bankMSB == MELODY_BANK ? melodies.get(key(c.bankLSB, c.program)) : null;
            if (voice == null) {
                voice = melodies.get(key(ANY, c.program));
            }
            if (voice == null && c.bankMSB != MELODY_BANK) {
                // a smaf "EXVO" voice is in a bank the channel has not selected
                voice = melodies.entrySet().stream()
                        .filter(e -> e.getValue().smaf && (e.getKey() & 0xff) == c.program)
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
        }
        return voice;
    }

    /** the wave a voice plays, null when it is not here */
    private Wave wave(Voice voice) {
        return (voice.romWave ? romWaves : waves).get(voice.waveId);
    }

    /** whether this can sound a voice, the wave of which is here */
    private boolean playable(Voice voice) {
        return voice != null &&
                (wave(voice) != null || (!voice.romWave && voice.smaf && smafEngine() != null));
    }

    /**
     * The SMAF wave engine an "EXWV" wave went into when the engine is not disabled, in
     * which case the wave never reaches here and an "EXVO" voice can only start it there.
     */
    private static AudioEngine smafEngine() {
        if (StreamExclusive.isEnabled()) {
            return null;
        }
        try {
            return WaveSequencer.Factory.getAudioEngine(SMAF_ADPCM);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** whether a wave table voice or a stream, rather than the OPL3, owns this note */
    synchronized boolean claims(int channel, int note) {
        return streamId(channel, note) != 0 || playable(voice(channel, note));
    }

    /**
     * Starts the wave of the voice or the stream this note plays.
     *
     * @return false when neither is here, the OPL3 should play it
     */
    synchronized boolean noteOn(int channel, int note, int velocity) {
        int streamId = streamId(channel, note);
        if (streamId != 0) {
            startStream(streamId, velocity, channel, note, -1);
            heldNotes.add(key(channel, note));
            return true;
        }

        Voice voice = voice(channel, note);
        if (!playable(voice)) {
            return false;
        }
        heldNotes.add(key(channel, note));
        Wave wave = wave(voice);
        if (wave == null) {
            // the "EXWV" of an "EXVO" went to the smaf wave engine, see smafEngine
            AudioEngine engine = smafEngine();
            AudioEngine.Sync.schedule(() -> engine.start(voice.waveId));
            return true;
        }
        if (voice.ar == 0) {
            return true; // the envelope never rises
        }

        short[] pcm = wave.pcm();
        int end = Math.min(voice.endPoint + 1, pcm.length);
        boolean loop = voice.loopPoint < voice.endPoint && voice.loopPoint < end;
        add(new Player(channel, note, NO_STREAM, pcm, null, voice.samplingRate, !isDrum(channel), loop ? voice.loopPoint : -1, end,
                velocity, voice));
        return true;
    }

    /**
     * Releases the voice or stops the stream of this note.
     *
     * @return false when the note on went to the OPL3
     */
    synchronized boolean noteOff(int channel, int note) {
        if (!heldNotes.remove(key(channel, note))) {
            return false;
        }
        for (Player player : players) {
            if (player.channel == channel && player.note == note) {
                if (player.streamId != NO_STREAM) {
                    player.stop();
                } else if (channels[channel].sustain) {
                    player.sustained = true;
                } else {
                    player.keyOff();
                }
            }
        }
        return true;
    }

    /**
     * Starts a stream an MFi audio message or a SMAF PCM audio track says to.
     *
     * @param audioChannel 0 ~ 3, else none
     */
    synchronized void streamOn(int id, int velocity, int audioChannel) {
        if (!streams.containsKey(id)) {
logger.log(Level.DEBUG, "stream on for no stream: " + id);
            return;
        }
        startStream(id, velocity, -1, -1, audioChannel >= 0 && audioChannel < audioVolumes.length ? audioChannel : -1);
    }

    /** Stops a stream an MFi audio message or a SMAF PCM audio track says to. */
    synchronized void streamOff(int id) {
        players.stream().filter(p -> p.streamId != NO_STREAM && (p.streamId == id || p.pairOf == id)).forEach(Player::stop);
    }

    private void startStream(int id, int velocity, int channel, int note, int audioChannel) {
        Stream stream = streams.get(id);
        // the same stream again starts over, the way a slot does
        players.removeIf(p -> p.streamId != NO_STREAM && (p.streamId == id || p.pairOf == id));
        startStream(id, NO_STREAM, stream, velocity, channel, note, audioChannel);
        if (stream.pair != 0 && streams.containsKey(stream.pair)) {
            players.removeIf(p -> p.streamId != NO_STREAM && p.streamId == stream.pair);
            startStream(stream.pair, id, streams.get(stream.pair), velocity, channel, note, audioChannel);
        }
    }

    private void startStream(int id, int pairOf, Stream stream, int velocity, int channel, int note, int audioChannel) {
        Player player = new Player(channel, note, id, stream.pcm[0], stream.pcm.length > 1 ? stream.pcm[1] : null,
                stream.samplingRate, false, -1, stream.pcm[0].length, velocity, null);
        player.pairOf = pairOf;
        player.audioChannel = audioChannel;
        add(player);
    }

    private void add(Player player) {
        if (players.size() >= MAX_PLAYERS) {
            players.removeFirst(); // the oldest
        }
        players.add(player);
    }

    // ---- sound

    /** the stream id of a player which plays a wave table voice, an MFi stream can be 0 */
    private static final int NO_STREAM = -1;

    /** attack time from silence to full [ms] of a rate, the OPL curve */
    private static double attackMillis(int rate) {
        return 2826.24 / (1 << (rate - 1));
    }

    /** decay time of 96dB [ms] of a rate, the OPL curve */
    private static double decayMillis(int rate) {
        return 39280.64 / (1 << (rate - 1));
    }

    /** the release rate of a voice whose SUS bit is set, as for an MA-3 FM operator */
    private static final int SUS_RELEASE_RATE = 4;

    /** attenuation at which a voice is over [dB] */
    private static final double SILENCE_DB = 96;

    /** one sounding wave */
    private class Player {
        /** midi channel, -1 for a stream an audio message started */
        final int channel;
        final int note;
        /** stream id, {@link #NO_STREAM} for a wave table voice */
        final int streamId;
        /** the stream this one was started with as a pair, {@link #NO_STREAM}: none */
        int pairOf = NO_STREAM;
        /** MFi audio channel, -1: none */
        int audioChannel = -1;
        final short[] left;
        /** null: mono */
        final short[] right;
        final int samplingRate;
        /** whether the pitch follows the note and the bend */
        final boolean keyFollow;
        /** [sample], -1: no loop */
        final int loopStart;
        /** [sample], exclusive */
        final int end;
        final double velocityGain;
        /** null for a stream */
        final Voice voice;

        double position;

        /** 0: attack, 1: decay, 2: sustain, 3: release */
        int stage;
        /** attack level 0 ~ 1 */
        double level;
        /** attenuation [dB] */
        double attenuation;
        /** a key off the sustain pedal holds */
        boolean sustained;
        boolean done;

        Player(int channel, int note, int streamId, short[] left, short[] right, int samplingRate, boolean keyFollow,
               int loopStart, int end, int velocity, Voice voice) {
            this.channel = channel;
            this.note = note;
            this.streamId = streamId;
            this.left = left;
            this.right = right;
            this.samplingRate = samplingRate;
            this.keyFollow = keyFollow;
            this.loopStart = loopStart;
            this.end = end;
            double v = velocity / 127.0;
            this.velocityGain = v * v;
            this.voice = voice;
            if (voice == null || voice.ar == 15) {
                level = 1;
                stage = 1;
            }
        }

        void keyOff() {
            if (voice == null) {
                stop();
            } else if (!voice.xof && stage < 3) {
                stage = 3;
                if (level < 1) { // released while rising
                    attenuation = Math.max(attenuation, -20 * Math.log10(Math.max(level, 1e-5)));
                    level = 1;
                }
            }
        }

        void stop() {
            done = true;
        }

        /** the envelope, one sample on */
        double envelope() {
            if (voice == null) {
                return 1;
            }
            double perSample = 1000.0 / sampleRate;
            switch (stage) {
                case 0 -> {
                    level += perSample / attackMillis(voice.ar);
                    if (level >= 1) {
                        level = 1;
                        stage = 1;
                    }
                }
                case 1 -> {
                    double sl = voice.sl == 15 ? 93 : voice.sl * 3;
                    if (voice.dr != 0) {
                        attenuation += SILENCE_DB * perSample / decayMillis(voice.dr);
                    }
                    if (attenuation >= sl) {
                        stage = 2;
                    }
                }
                case 2 -> {
                    if (voice.sr != 0) {
                        attenuation += SILENCE_DB * perSample / decayMillis(voice.sr);
                    }
                }
                default -> {
                    int rate = voice.sus ? Math.min(voice.rr, SUS_RELEASE_RATE) : voice.rr;
                    if (rate != 0) {
                        attenuation += SILENCE_DB * perSample / decayMillis(rate);
                    }
                }
            }
            if (attenuation >= SILENCE_DB) {
                done = true;
                return 0;
            }
            return level * Math.pow(10, -attenuation / 20);
        }

        /** adds this to the output */
        void render(int[][] buffer, int length) {
            Channel c = channel >= 0 ? channels[channel] : null;

            double ratio = (double) samplingRate;
            if (keyFollow && c != null) {
                double semitones = note - BASE_KEY + c.bend * c.bendRange / 8192.0;
                ratio *= Math.pow(2, semitones / 12);
            }
            double step = Math.min(ratio, MAX_FS) / sampleRate;

            double gain = GAIN * velocityGain;
            double pan;
            if (voice != null) {
                gain *= Math.pow(10, -0.75 * voice.tl / 20);
            }
            if (c != null) {
                double volume = c.volume / 127.0;
                double expression = c.expression / 127.0;
                gain *= volume * volume * expression * expression;
            }
            if (audioChannel >= 0) {
                double volume = audioVolumes[audioChannel] / 127.0;
                gain *= volume * volume;
            }
            Stream stream = streamId != NO_STREAM ? streams.get(streamId) : null;
            if (voice != null && voice.panEnable) {
                pan = voice.panpot / 31.0;
            } else if (stream != null && stream.panpot >= 0) {
                pan = stream.panpot / 127.0;
            } else if (right == null && audioChannel >= 0) {
                pan = audioPanpots[audioChannel] / 127.0;
            } else if (right == null && c != null) {
                pan = c.pan / 127.0;
            } else {
                pan = 0.5;
            }
            double gainL = gain * Math.min(1, 2 * (1 - pan));
            double gainR = gain * Math.min(1, 2 * pan);

            for (int i = 0; i < length && !done; i++) {
                int index = (int) position;
                if (index >= end) {
                    if (loopStart >= 0) {
                        position -= end - loopStart;
                        index = (int) position;
                    } else {
                        done = true;
                        break;
                    }
                }
                double env = envelope();
                double fraction = position - index;
                int next = index + 1 < end ? index + 1 : loopStart >= 0 ? loopStart : index;
                double l = left[index] + (left[next] - left[index]) * fraction;
                double r = right != null ? right[index] + (right[next] - right[index]) * fraction : l;
                buffer[0][i] += (int) (l * env * gainL);
                buffer[1][i] += (int) (r * env * gainR);
                position += step;
            }
        }
    }

    /**
     * Adds what sounds to a buffer the OPL3 has written.
     *
     * @param buffer [0]: left, [1]: right
     */
    synchronized void render(int[][] buffer, int length) {
        if (players.isEmpty()) {
            return;
        }
        for (Iterator<Player> i = players.iterator(); i.hasNext(); ) {
            Player player = i.next();
            player.render(buffer, length);
            if (player.done) {
                i.remove();
            }
        }
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < length; i++) {
                buffer[c][i] = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, buffer[c][i]));
            }
        }
    }

    /** how many waves sound now */
    synchronized int getPlayers() {
        return players.size();
    }

    /** stops everything, the SMAF wave engine is not ours to close */
    synchronized void close() {
        players.clear();
        heldNotes.clear();
    }

    /** 4 bit YAMAHA adpcm to 16 bit pcm */
    private static short[] decodeAdpcm(byte[] adpcm, int offset, int length) {
        try (MaInputStream in = new MaInputStream(new ByteArrayInputStream(adpcm, offset, length), ByteOrder.LITTLE_ENDIAN)) {
            byte[] bytes = in.readAllBytes();
            short[] pcm = new short[bytes.length / 2];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
            }
            return pcm;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
