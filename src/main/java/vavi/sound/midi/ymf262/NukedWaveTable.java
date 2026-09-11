/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import vavi.sound.mobile.AudioEngine;
import vavi.sound.mobile.YamahaAudioEngine;
import vavi.sound.smaf.sequencer.WaveSequencer;

import static java.lang.System.getLogger;


/**
 * The MA-3 / MA-5 wave table (WT) voices of a {@link NukedSynthesizer}.
 * <p>
 * OPL3 is FM only, so a WT voice cannot become an
 * {@link vavi.sound.midi.ymf262.NukedPlayer.opl_timbre} the way an FM voice does.
 * It does not have to: a WT voice is a 4 bit ADPCM wave plus the rate to play it
 * at, which is exactly what {@link AudioEngine} already plays for the MFi 4.0
 * audio messages and the SMAF stream PCM. So the wave goes into the engine as a
 * stream and a note on starts it, next to the OPL3 output rather than through it.
 * </p>
 * <p>
 * The two messages this is fed from are
 * </p>
 * <pre>
 *  43 79 07 7f 01 mm ll pc dn 01 &lt;16 byte VM35 PCM voice&gt; f7   {@link #setVoice}
 *  43 05 00 &lt;wave id&gt; &lt;4 bit adpcm&gt; f7                        {@link #setWave}
 *  43 05 02 bb pp &lt;16 byte VM35 PCM voice&gt; f7                  {@link #setSmafVoice}
 * </pre>
 * <p>
 * The first two are what {@code vavi.sound.mfi.vavi.nec.Function1_240_5 / _6 / _8}
 * and {@code Function2_240_12} of an MFi file send, see
 * {@code vavi.sound.mfi.vavi.sequencer.SmafExclusive}. The third is the "EXVO"
 * chunk of a SMAF file, whose wave does <em>not</em> travel as an exclusive: the
 * "EXWV" chunk next to it goes straight into {@link WaveSequencer.Factory}'s
 * engine as stream {@code wave id}
 * ({@code vavi.sound.smaf.message.WaveDataMessage}), so for those this only has
 * to say which patch plays which wave.
 * </p>
 * <p>
 * What the engine cannot do, and this therefore does not model: the note does not
 * transpose the wave (it plays at its own {@code Fs}), the loop point is ignored
 * (the wave plays once, from its start address offset to its end point), and the
 * envelope, TL and panpot of the voice are not applied - the engine has one
 * global volume ({@code vavi.sound.mobile.AudioEngine.volume}). That covers the
 * percussion and SFX voices WT is mostly used for; a sustained melodic WT voice
 * will be flat.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-11 nsano initial version <br>
 * @see "https://github.com/but80/smaf825/blob/v1/smaf/voice/vm35_pcm_voice.go"
 */
class NukedWaveTable {

    private static final Logger logger = getLogger(NukedWaveTable.class.getName());

    /** streams one {@link AudioEngine} holds */
    private static final int STREAMS = 32;

    /** general midi rhythm channel */
    private static final int DRUM_CHANNEL = 9;

    /**
     * The channel number every stream is registered with.
     * <p>
     * {@link YamahaAudioEngine#getChannels} pairs an even stream whose channel is
     * even with its odd neighbour into one stereo stream, which is what a SMAF
     * stereo pair needs and what a wave table voice must not be. An odd channel
     * number fails that test for every stream number, so all 32 stay mono.
     * </p>
     */
    private static final int MONO = 1;

    /** 4 bit adpcm, 2 samples per byte */
    private static final int SAMPLES_PER_BYTE = 2;

    /** the wave format a SMAF "EXWV" is stored under, see {@code WaveType} */
    private static final int SMAF_ADPCM = 1;

    /** one registered wave table voice, the 16 byte VM35 PCM voice image */
    static class Voice {

        /** 1 ~ 48000 [Hz] */
        final int samplingRate;
        /** [sample] */
        final int startOffset;
        /** [sample] */
        final int endPoint;
        /** true: a preset (rom) wave, which there is no data for here */
        final boolean romWave;
        /** the wave this voice plays, when {@link #romWave} is false */
        final int waveId;
        /** the wave belongs to the SMAF wave engine, this does not own it */
        final boolean smaf;
        /** the engine which holds the wave, resolved when the wave is there */
        AudioEngine engine;
        /** stream number inside {@link #engine}, -1 until the wave arrives */
        int streamNumber = -1;

        Voice(byte[] image) {
            this(image, false);
        }

        Voice(byte[] image, boolean smaf) {
            this.smaf = smaf;
            this.samplingRate = ((image[ 0] & 0xff) << 8) | (image[ 1] & 0xff);
            this.startOffset  = ((image[ 9] & 0xff) << 8) | (image[10] & 0xff);
            this.endPoint     = ((image[13] & 0xff) << 8) | (image[14] & 0xff);
            this.romWave      =  (image[15] & 0x80) != 0;
            this.waveId       =   image[15] & 0x7f;
        }

        @Override
        public String toString() {
            return samplingRate + "Hz, " + startOffset + " ~ " + endPoint +
                    ", " + (romWave ? "rom wave " : "wave ") + waveId + (smaf ? " (smaf)" : "");
        }
    }

    /** wave id -> 4 bit adpcm */
    private final Map<Integer, byte[]> waves = new ConcurrentHashMap<>();

    /** patch ({@link #key}) -> voice */
    private final Map<Integer, Voice> voices = new ConcurrentHashMap<>();

    /**
     * sampling rate -> engine. One engine owns one line whose format is fixed by
     * the last {@code setData}, so voices of different rates cannot share one.
     */
    private final Map<Integer, AudioEngine> engines = new ConcurrentHashMap<>();

    /** sampling rate -> how many streams of that engine are taken */
    private final Map<Integer, Integer> allocated = new ConcurrentHashMap<>();

    /** current program of a midi channel */
    private final int[] programs = new int[16];

    /** the patch a note plays, the same key {@code NukedSynthesizer#registerVoice} uses */
    private int noteKey(int channel, int note) {
        return channel == DRUM_CHANNEL ? (128 << 8) | (128 + note) : programs[channel] & 0x7f;
    }

    /** the patch a registered voice is for */
    private static int voiceKey(int program, int drumNote) {
        return drumNote != 0 ? (128 << 8) | (128 + drumNote) : program & 0x7f;
    }

    /**
     * Registers a wave table voice.
     *
     * @param program the program (pc) byte of the voice exclusive
     * @param drumNote the drum note byte, 0 for a melody voice
     * @param image the 16 byte VM35 PCM voice
     */
    void setVoice(int program, int drumNote, byte[] image) {
        if (image.length < 16) {
logger.log(Level.WARNING, "wave table voice is too short: " + image.length);
            return;
        }
        Voice voice = new Voice(image);
        voices.put(voiceKey(program, drumNote), voice);
logger.log(Level.DEBUG, "wave table voice: program: " + program +
        (drumNote != 0 ? ", drum note: " + drumNote : "") + ", " + voice);
        bind(voice);
    }

    /**
     * Registers a SMAF "EXVO" wave table voice, whose wave is already in the SMAF
     * wave engine.
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
     * @see vavi.sound.smaf.chunk.ExclusiveVoiceChunk
     */
    void setSmafVoice(int bank, int program, byte[] image) {
        if (image.length < 16) {
logger.log(Level.WARNING, "smaf wave table voice is too short: " + image.length);
            return;
        }
        boolean drum = (bank & 0x80) != 0;
        Voice voice = new Voice(image, true);
        voices.put(voiceKey(drum ? 0 : program, drum ? program : 0), voice);
logger.log(Level.DEBUG, "smaf wave table voice: bank: %02x, program: %d, ".formatted(bank, program) + voice);
        bind(voice);
    }

    /**
     * Registers the wave a voice plays.
     *
     * @param waveId what the {@code RM, WaveID} byte of a voice refers to
     * @param adpcm 4 bit adpcm
     */
    void setWave(int waveId, byte[] adpcm) {
        waves.put(waveId, adpcm);
logger.log(Level.DEBUG, "wave table wave: No." + waveId + ", " + adpcm.length + " bytes adpcm");
        for (Voice voice : voices.values()) {
            if (!voice.romWave && voice.waveId == waveId) {
                bind(voice);
            }
        }
    }

    /** Hands a voice's wave to the engine of its sampling rate. */
    private void bind(Voice voice) {
        if (voice.romWave) {
logger.log(Level.DEBUG, "wave table voice wants rom wave " + voice.waveId + ", which there is no data for");
            return;
        }
        if (voice.samplingRate < 1 || voice.samplingRate > 48000) {
logger.log(Level.WARNING, "wave table voice has a bad sampling rate: " + voice.samplingRate);
            return;
        }
        if (voice.smaf) {
            // the "EXWV" wave went straight into the smaf wave engine as stream
            // <wave id>, nothing to hand over, only which stream to start
            try {
                voice.engine = WaveSequencer.Factory.getAudioEngine(SMAF_ADPCM);
            } catch (IllegalArgumentException e) {
logger.log(Level.WARNING, "no audio engine for the smaf wave format: " + e);
                return;
            }
            voice.streamNumber = voice.waveId;
            return;
        }

        byte[] wave = waves.get(voice.waveId);
        if (wave == null) {
            return; // the wave message has not arrived yet, setWave binds it then
        }

        if (voice.streamNumber < 0) {
            voice.streamNumber = allocated.merge(voice.samplingRate, 1, Integer::sum) - 1;
            if (voice.streamNumber >= STREAMS) {
logger.log(Level.WARNING, "more than " + STREAMS + " wave table voices at " + voice.samplingRate + "Hz, reusing a stream");
                voice.streamNumber %= STREAMS;
            }
        }

        byte[] slice = slice(wave, voice);
        voice.engine = engine(voice.samplingRate);
        voice.engine.setData(voice.streamNumber, MONO, voice.samplingRate, 4, 1, slice, false);
logger.log(Level.DEBUG, "wave table stream " + voice.streamNumber + "@" + voice.samplingRate + "Hz: " + slice.length + " bytes");
    }

    /** the part of the wave the voice plays */
    private static byte[] slice(byte[] wave, Voice voice) {
        int from = Math.min(voice.startOffset / SAMPLES_PER_BYTE, wave.length);
        int to = voice.endPoint > 0 ?
                Math.min((voice.endPoint + 1) / SAMPLES_PER_BYTE, wave.length) : wave.length;
        return to > from ? Arrays.copyOfRange(wave, from, to) : wave;
    }

    /** */
    private AudioEngine engine(int samplingRate) {
        return engines.computeIfAbsent(samplingRate, r -> new YamahaAudioEngine());
    }

    /** Remembers what patch a channel plays. */
    void programChange(int channel, int program) {
        if (channel >= 0 && channel < programs.length) {
            programs[channel] = program;
        }
    }

    /** the wave registered for an id, null when there is none */
    byte[] getWave(int waveId) {
        return waves.get(waveId);
    }

    /** whether a wave table voice, rather than the OPL3, owns this note */
    boolean claims(int channel, int note) {
        Voice voice = voices.get(noteKey(channel, note));
        return voice != null && voice.streamNumber >= 0 && voice.engine != null;
    }

    /**
     * Starts the wave of the voice this note plays.
     *
     * @return false when no wave table voice is registered for it, the OPL3 should play it
     */
    boolean noteOn(int channel, int note, int velocity) {
        Voice voice = voices.get(noteKey(channel, note));
        if (voice == null || voice.streamNumber < 0 || voice.engine == null) {
            return false;
        }
        AudioEngine engine = voice.engine;
        int streamNumber = voice.streamNumber;
        // the wave plays to its end, a note off does not cut it - AudioEngine#stop
        // stops the whole line, and a WT voice is nearly always a percussion or
        // sfx one shot whose XOF bit says to ignore the key off anyway
        AudioEngine.Sync.schedule(() -> engine.start(streamNumber));
        return true;
    }

    /** closes the engines this owns, the SMAF wave engine is not ours to close */
    void close() {
        engines.values().forEach(AudioEngine::close);
        engines.clear();
        allocated.clear();
    }
}
