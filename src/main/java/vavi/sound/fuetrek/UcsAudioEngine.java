/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.fuetrek;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mobile.AudioEngineMixer;

import static java.lang.System.getLogger;


/**
 * The fuetrek sound source in pure java: the preset tones of {@link FuetrekRom}
 * and the UCS user waves of {@link UcsWaveBank}, played by midi channel messages.
 * <p>
 * The midi is the one vavi converts mfi into, so the values are the mfi ones doubled
 * and the keys are already the note bytes of the sound source.
 * <ul>
 * <li>a (bank, program) a UCS wave is assigned to ... the UCS wave</li>
 * <li>bank 0 ... group 0x7d, bank 1 ~ 0x33 ... the melody group 0x79 (odd banks + 0x40)</li>
 * <li>channel 9 ... the drum group 0x78 by key, bank 0x34 ... group 0x14 by key</li>
 * </ul>
 * system property
 * <li>{@code vavi.sound.fuetrek.dump} ... a file what is played is written to too, raw pcm 32 kHz 16 bit stereo little endian</li>
 * <p>
 * What a song of a phone brings besides the midi is told by the one who plays it, see
 * {@code vavi.sound.mfi.fuetrek.FuetrekMfiSynthesizer}: the bank of a channel ({@link #bankChange}, without
 * it the midi program is taken as the melody group's, bank 2, 3), the fine half of the pitch bend
 * ({@link #pitchBendFine}) and 0xe7 ({@link #mfiPitchBendRange}), which the native player takes as
 * a modulation lane rather than the bend range.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
public final class UcsAudioEngine implements AutoCloseable {

    private static final Logger logger = getLogger(UcsAudioEngine.class.getName());

    /** the native rate, the rom steps are for it */
    public static final int SAMPLE_RATE = 32_000;
    /** the control block */
    public static final int BLOCK = 128;
    /** rt_synth_4.dll has 48 in its voice sets 0 and 1 (64 in 2) */
    public static final int POLYPHONY = 48;
    private static final int CHANNEL_DRUM = 9;

    /** global mix state, the universal device controls */
    static final class Mix {
        /** host master volume, gain byte */
        int host = 0x7f;
        /** mfi master volume */
        int master = 0x7f;
        /** master balance, signed -64 ~ 63 */
        int masterPan;
        /** master fine tuning, signed 14 bit, 0x2000 is a semitone */
        int masterFine;
        /** master coarse tuning [semitones] */
        int masterCoarse;

        int globalVolume() {
            return Math.clamp(host, 0, 0x7f) * Math.clamp(master, 0, 0x7f) / 0x7f;
        }
    }

    /** the most bend reaches [Q16 semitones], a parameter of RTPSynthOpen */
    private static final int BEND_LIMIT = 24 << 16;

    /** a midi channel, as the dll keeps one */
    static final class Channel {
        final int index;
        final Mix mix;
        /** drum by bank select msb (even) or by channel 9 */
        boolean drum;
        /** bank select msb waiting for a program change, the rom group */
        int groupPending;
        /** the rom group, bank select msb latched by a program change */
        int group;
        /** bank select lsb, the sub group */
        int subGroup;
        /** mfi bank, -1: not told (vavi's f0 45 04), the mfi selector is used when told */
        int bank = -1;
        int program;
        int volume = 0x64;
        int expression = 0x7f;
        /** signed -64 ~ 63 */
        int pan;
        /** signed 14 bit */
        int bend;
        /** semitones << 7 | cents */
        int sensitivity = 2 << 7;
        /** signed 14 bit, 0x2000 is a semitone */
        int fine;
        /** semitones */
        int coarse;
        int modulation;
        int pressure;
        /** mfi 0xe7 doubled, a modulation lane to the native player */
        int modulationLane;
        /** the rpn 0 data entry following is mfi 0xe7's, not a bend range */
        boolean mfiBendRange;
        /** mfi pitch bend halves, 6 bits each, 32 is neutral, -1: the fine one is not told */
        int bendHigh = 0x20, bendLow = -1;
        /** note ons of a key not yet off, the native player does not strike a sounding key again */
        final int[] keysOn = new int[128];
        boolean hold;
        /** 1: rpn, 2: nrpn, 0: none */
        int parameterType;
        int rpnMsb = 0x7f, rpnLsb = 0x7f, nrpnMsb = 0x7f, nrpnLsb = 0x7f;

        Channel(int index, Mix mix) {
            this.index = index;
            this.mix = mix;
            reset();
        }

        /** as the dll resets a channel (gm system on) */
        void reset() {
            java.util.Arrays.fill(keysOn, 0);
            drum = index == CHANNEL_DRUM;
            group = groupPending = drum ? FuetrekRom.GROUP_DRUM : FuetrekRom.GROUP_MELODY;
            subGroup = 0;
            bank = -1;
            program = 0;
            volume = 0x64;
            pan = 0;
            sensitivity = 2 << 7;
            fine = 0;
            coarse = 0;
            resetControllers();
        }

        /** reset all controllers (121) */
        void resetControllers() {
            expression = 0x7f;
            modulation = 0;
            pressure = 0;
            hold = false;
            bend = 0;
            bendHigh = 0x20;
            bendLow = -1;
            modulationLane = 0;
            mfiBendRange = false;
            parameterType = 0;
            rpnMsb = rpnLsb = nrpnMsb = nrpnLsb = 0x7f;
        }

        boolean isDrum() {
            return drum;
        }

        /** modulation, channel pressure and mfi 0xe7 together */
        int modulation() {
            return Math.clamp(modulation + pressure + modulationLane, 0, 0x7f);
        }

        /** the pitch bend word of the halves, as the native player makes it */
        void updateBend() {
            int word = bendLow < 0 ? (bendHigh << 8) : ((((bendHigh & 0x3f) << 5) + (bendLow & 0x3f)) << 3) - 0x100;
            bend = Math.clamp(word, 0, 0x3fff) - 0x2000;
        }

        /** bend, channel and master tunings [Q16 semitones] */
        int pitchQ16() {
            int bendQ16 = Math.clamp((bend * sensitivity) >> 4, -BEND_LIMIT, BEND_LIMIT);
            return bendQ16 + 8 * (((coarse + mix.masterCoarse) << 13) + fine + mix.masterFine);
        }
    }

    private final FuetrekRom rom;
    private final Mix mix = new Mix();
    private final Channel[] channels = new Channel[16];
    private final FuetrekVoice[] voices = new FuetrekVoice[POLYPHONY];
    private long age;
    private int counter = 0x78;

    private final Object lock = new Object();
    /** false: {@link #render(byte[], int)} is called by the user, no line is opened */
    private final boolean realtime;
    private volatile boolean running;
    private SourceDataLine line;

    public UcsAudioEngine() throws IOException {
        this(FuetrekRom.getInstance());
    }

    public UcsAudioEngine(FuetrekRom rom) {
        this(rom, true);
    }

    /** @param realtime false: renders only by {@link #render(byte[], int)} */
    public UcsAudioEngine(FuetrekRom rom, boolean realtime) {
        this.rom = rom;
        this.realtime = realtime;
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel(i, mix);
        }
    }

    // ---- midi

    public void noteOn(int channel, int key, int velocity) {
        if (velocity == 0) {
            noteOff(channel, key);
            return;
        }
        synchronized (lock) {
            Channel c = channels[channel];
            // a key struck again while it sounds goes on, as the native player does
            // (mfi notes longer than a gate time overlap the next by a tick)
            if (c.keysOn[key & 0x7f]++ > 0) return;
            int note = key;
            while (note < 0x15) note += 12;
            while (note > 0x78) note -= 12;

            FuetrekVoice voice = c.isDrum() ? drum(c, key, note, velocity) : melody(c, key, note, velocity);
            if (voice == null) return;
            int slot = -1;
            for (int i = 0; i < voices.length; i++) {
                if (voices[i] == null) {
                    slot = i;
                    break;
                }
                if (slot == -1 || voices[i].age < voices[slot].age) slot = i;
            }
            voices[slot] = voice;
        }
        ensureStarted();
    }

    private FuetrekVoice melody(Channel c, int key, int note, int velocity) {
        release(c, key);
        List<UcsWaveBank.Wave> waves = c.bank < 0 ? UcsWaveBank.getInstance().tone(c.program)
                : UcsWaveBank.getInstance().tone(c.bank, c.program & 0x3f);
        if (!waves.isEmpty()) {
            return ucs(c, key, note, velocity, waves);
        }
        int group, index;
        if (c.bank < 0) {
            // the dll: bank select msb is the group
            group = c.group;
            index = c.program;
        } else if (c.bank == 0) {
            group = 0x7d;
            index = c.program & 0x3f;
        } else if (c.bank == 0x36) {
            group = 0x11;
            index = c.program & 0x3f;
        } else if (c.bank < 0x34) {
            group = FuetrekRom.GROUP_MELODY;
            index = (c.program & 0x3f) + ((c.bank & 1) != 0 ? 0x40 : 0);
        } else {
            return null;
        }
        return voice(c, key, note, velocity, group, index);
    }

    private FuetrekVoice drum(Channel c, int key, int note, int velocity) {
        int group;
        if (c.bank < 0) {
            group = c.group;
        } else if (c.bank == 0x34) {
            group = 0x14;
        } else if (c.bank == 0x36) {
            group = 0x10;
        } else if (c.bank < 0x34) {
            group = FuetrekRom.GROUP_DRUM;
        } else {
            return null;
        }
        return voice(c, key, note, velocity, group, c.program & 0x7f);
    }

    /**
     * a group the rom does not have is the melody or the drum one by its bit 0,
     * a melody (odd) group is looked up by the program, a drum (even) one by the note
     */
    private FuetrekVoice voice(Channel c, int key, int note, int velocity, int group, int program) {
        if (!hasGroup(group)) {
            group = (group & 1) != 0 ? FuetrekRom.GROUP_MELODY : FuetrekRom.GROUP_DRUM;
        }
        int index = (group & 1) != 0 ? program : note;
        FuetrekRom.Instrument instrument = rom.instrument(group, index);
        FuetrekRom.Zone zone = instrument == null ? null : instrument.zone(note);
        if (zone == null || zone.sampleA == null) return null;
        if ((group & 1) == 0) {
            // the same note of a drum group stops at once
            for (int i = 0; i < voices.length; i++) {
                if (voices[i] != null && voices[i].channel == c && voices[i].group == group && voices[i].note == note) {
                    voices[i] = null;
                }
            }
        }
        return new FuetrekVoice(rom, c, key, note, velocity, group, index,
                zone.sampleA, zone.sampleB, zone.s8(0x10), zone.s16(0x12), zone.s32(0x3c), FuetrekVoice.Template.of(zone), age++);
    }

    private boolean hasGroup(int group) {
        for (int g : rom.groups()) if (g == group) return true;
        return false;
    }

    /** the wave whose root key is the nearest, with the voice parameters of its own */
    private FuetrekVoice ucs(Channel c, int key, int note, int velocity, List<UcsWaveBank.Wave> waves) {
        UcsWaveBank.Wave wave = waves.getFirst();
        for (UcsWaveBank.Wave candidate : waves) {
            if (Math.abs(note - candidate.rootPitch) < Math.abs(note - wave.rootPitch)) wave = candidate;
        }
        byte[] parameters = wave.parameters != null ? wave.parameters : new byte[0];
        FuetrekVoice.Template template = parameters.length >= 44 ? FuetrekVoice.Template.of(rom, parameters) : plain();
        int tune = parameters.length >= 8 ? rom.rootKeyTune(parameters[7] & 0xff) : 0;
        FuetrekRom.Sample sample = new FuetrekRom.Sample("fuetrek", wave.pcm(), wave.loopStart, wave.loopEnd,
                tune != 0 ? tune : 0x400, (int) wave.rootPitch, 0);
        return new FuetrekVoice(rom, c, key, note, velocity, FuetrekRom.GROUP_MELODY, c.program,
                sample, sample, 0, 0, -1, template, age++);
    }

    /** a template for a UCS wave without parameters: an organ like gate */
    private static FuetrekVoice.Template plain() {
        FuetrekVoice.Template t = new FuetrekVoice.Template();
        t.ampA = 0x1ff;
        t.zoneGain = 0x1e;
        t.envA6 = 0x7c0;
        t.envA8 = 0x800;
        t.envAA = 0x700;
        t.envAE = 0x1f;
        t.envB2 = 0x7c0;
        t.envB4 = 0x800;
        t.envB6 = 0x7c0;
        t.envBA = 0x1f;
        t.envBC = 0x7ff;
        t.shapeW2 = 0x3ff;
        return t;
    }

    public void noteOff(int channel, int key) {
        synchronized (lock) {
            Channel c = channels[channel];
            if (c.keysOn[key & 0x7f] > 0 && --c.keysOn[key & 0x7f] > 0) return;
            release(c, key);
        }
    }


    /** a note off, which the hold pedal keeps sounding */
    private void release(Channel c, int key) {
        for (FuetrekVoice voice : voices) {
            if (voice != null && voice.channel == c && voice.key == key && !voice.isReleased()) {
                if (c.hold) {
                    voice.held = true;
                } else {
                    voice.release();
                }
            }
        }
    }

    /** @param bank mfi bank */
    public void bankChange(int channel, int bank) {
        synchronized (lock) {
            channels[channel].bank = bank & 0x3f;
        }
    }

    /** the bank select msb waiting is latched */
    public void programChange(int channel, int program) {
        synchronized (lock) {
            Channel c = channels[channel];
            c.program = program & 0x7f;
            c.group = c.groupPending;
        }
    }

    public void channelPressure(int channel, int value) {
        synchronized (lock) {
            Channel c = channels[channel];
            c.pressure = value & 0x7f;
            forEach(c, v -> v.modulation(c.modulation()));
        }
    }

    public void controlChange(int channel, int control, int value) {
        synchronized (lock) {
            Channel c = channels[channel];
            switch (control) {
            case 0 -> {
                // the group, even ones are drums, 0 is the default of the channel
                if (value != 0) {
                    c.groupPending = value;
                    c.drum = (value & 1) == 0;
                } else {
                    c.drum = c.index == CHANNEL_DRUM;
                    c.groupPending = c.drum ? FuetrekRom.GROUP_DRUM : FuetrekRom.GROUP_MELODY;
                }
            }
            case 32 -> c.subGroup = value;
            case 1 -> {
                c.modulation = value;
                forEach(c, v -> v.modulation(c.modulation()));
            }
            case 7 -> c.volume = value;
            case 10 -> c.pan = Math.clamp(value - 0x40, -0x40, 0x3f);
            case 11 -> c.expression = value;
            case 64 -> {
                c.hold = value >= 0x40;
                if (!c.hold) forEach(c, v -> {
                    if (v.held) {
                        v.held = false;
                        v.release();
                    }
                });
            }
            case 101 -> {
                c.rpnMsb = value;
                if (c.rpnLsb != 0x7f) c.parameterType = 1;
            }
            case 100 -> {
                c.rpnLsb = value;
                if (c.rpnMsb != 0x7f) c.parameterType = 1;
            }
            case 99 -> {
                c.nrpnMsb = value;
                if (c.nrpnLsb != 0x7f) c.parameterType = 2;
            }
            case 98 -> {
                c.nrpnLsb = value;
                if (c.nrpnMsb != 0x7f) c.parameterType = 2;
            }
            case 6 -> dataEntry(c, value, true);
            case 38 -> dataEntry(c, value, false);
            case 96 -> dataStep(c, 1);
            case 97 -> dataStep(c, -1);
            case 120 -> {
                if (value == 0) java.util.Arrays.fill(c.keysOn, 0);
                if (value == 0) forEach(c, v -> {
                    for (int i = 0; i < voices.length; i++) if (voices[i] == v) voices[i] = null;
                });
            }
            case 121 -> {
                if (value == 0) {
                    c.resetControllers();
                    forEach(c, v -> {
                        v.modulation(c.modulation());
                        v.pitch(c.pitchQ16());
                        if (v.held) {
                            v.held = false;
                            v.release();
                        }
                    });
                }
            }
            case 123 -> {
                if (value == 0) java.util.Arrays.fill(c.keysOn, 0);
                if (value == 0) forEach(c, v -> {
                    if (c.hold) v.held = true;
                    else v.release();
                });
            }
            default -> {}
            }
        }
    }

    /** rpn 0: bend sensitivity, 1: fine tuning, 2: coarse tuning, nrpn: none */
    private void dataEntry(Channel c, int value, boolean msb) {
        if (c.parameterType != 1 || c.rpnMsb != 0) return;
        if (msb && c.rpnLsb == 0 && c.mfiBendRange) {
            // mfi 0xe7, taken by mfiPitchBendRange
            c.mfiBendRange = false;
            return;
        }
        switch (c.rpnLsb) {
        // the dll adds an lsb to the word rather than replacing the low bits
        case 0 -> c.sensitivity = msb ? value << 7 : c.sensitivity + value;
        case 1 -> c.fine = msb ? (value - 0x40) << 7 : c.fine + value;
        case 2 -> {
            if (msb) c.coarse = value - 0x40;
        }
        default -> { return; }
        }
        forEach(c, v -> v.pitch(c.pitchQ16()));
    }

    /** data increment (96) / decrement (97) */
    private void dataStep(Channel c, int direction) {
        if (c.parameterType != 1 || c.rpnMsb != 0) return;
        switch (c.rpnLsb) {
        case 0 -> c.sensitivity = Math.clamp(c.sensitivity + direction * 0x80, 0, 0x3fff);
        case 1 -> c.fine = Math.clamp(c.fine + direction * 0x80, -0x2000, 0x1fff);
        case 2 -> c.coarse = Math.clamp(c.coarse + direction, -0x40, 0x3f);
        default -> { return; }
        }
        forEach(c, v -> v.pitch(c.pitchQ16()));
    }

    /** @param value 14 bit, mfi 0xe4 is in the msb doubled, the fine half by {@link #pitchBendFine} */
    public void pitchBend(int channel, int value) {
        synchronized (lock) {
            Channel c = channels[channel];
            if (c.bendLow < 0) {
                c.bend = Math.clamp(value, 0, 0x3fff) - 0x2000;
            } else {
                c.bendHigh = (value >> 8) & 0x3f;
                c.updateBend();
            }
            forEach(c, v -> v.pitch(c.pitchQ16()));
        }
    }

    /** @param fine mfi 0xe9, the low 6 bits of the pitch bend, cached until the next pitch bend */
    public void pitchBendFine(int channel, int fine) {
        synchronized (lock) {
            channels[channel].bendLow = fine & 0x3f;
        }
    }

    /**
     * mfi 0xe7: the native player takes it as a modulation lane, not as the bend range,
     * the rpn 0 vavi makes of it following is not taken.
     * @param value 6 bit
     */
    public void mfiPitchBendRange(int channel, int value) {
        synchronized (lock) {
            Channel c = channels[channel];
            c.modulationLane = Math.clamp(value << 1, 0, 0x7f);
            c.mfiBendRange = true;
            forEach(c, v -> v.modulation(c.modulation()));
        }
    }

    /** @param volume 0 ~ 1 host volume */
    public void hostVolume(double volume) {
        mix.host = rom.gainByte(volume);
    }

    /** @param volume mfi master volume 0 ~ 127 */
    public void masterVolume(int volume) {
        mix.master = volume;
    }

    /** @param balance universal master balance msb, 0x40 is center */
    public void masterBalance(int balance) {
        mix.masterPan = Math.clamp(balance - 0x40, -0x40, 0x3f);
    }

    /** @param value universal master fine tuning, 14 bit, 0x2000 is center */
    public void masterFineTuning(int value) {
        synchronized (lock) {
            mix.masterFine = value - 0x2000;
            updatePitches();
        }
    }

    /** @param msb universal master coarse tuning msb, 0x40 is center */
    public void masterCoarseTuning(int msb) {
        synchronized (lock) {
            mix.masterCoarse = msb - 0x40;
            updatePitches();
        }
    }

    /** gm system on: all channels to their defaults, the notes stay as the dll does */
    public void reset() {
        synchronized (lock) {
            for (Channel c : channels) c.reset();
            updatePitches();
        }
    }

    private void updatePitches() {
        for (FuetrekVoice voice : voices) {
            if (voice != null) voice.pitch(voice.channel.pitchQ16());
        }
    }

    private void forEach(Channel c, java.util.function.Consumer<FuetrekVoice> action) {
        for (FuetrekVoice voice : voices.clone()) {
            if (voice != null && voice.channel == c) action.accept(voice);
        }
    }

    // ---- audio

    /**
     * @param pcm 16 bit little endian stereo
     * @param frames frames to render
     */
    public void render(byte[] pcm, int frames) {
        synchronized (lock) {
            int[] left = new int[frames], right = new int[frames];
            // the native synthesizer renders a voice through the block and then the next one,
            // the control counter goes on for every frame of every voice
            for (int i = 0; i < voices.length; i++) {
                FuetrekVoice voice = voices[i];
                if (voice == null) continue;
                boolean active = true;
                for (int f = 0; f < frames; f++) {
                    if (active) {
                        active = voice.render(counter);
                        if (active) {
                            left[f] += voice.left;
                            right[f] += voice.right;
                        }
                    }
                    counter = (counter + 1) & (BLOCK - 1);
                }
                if (!active) voices[i] = null;
            }
            for (int f = 0; f < frames; f++) {
                int l = FuetrekVoice.clamp16(left[f]);
                int r = FuetrekVoice.clamp16(right[f]);
                pcm[f * 4] = (byte) l;
                pcm[f * 4 + 1] = (byte) (l >> 8);
                pcm[f * 4 + 2] = (byte) r;
                pcm[f * 4 + 3] = (byte) (r >> 8);
            }
        }
    }

    /**
     * Starts the line of a realtime engine, which a note does anyway: the adpcm of a song may
     * come before its first note, and it is mixed into this line only once the line is there.
     */
    public void startOutput() {
        ensureStarted();
    }

    private synchronized void ensureStarted() {
        if (running || !realtime) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BLOCK * 4 * 8);
            line.start();
            running = true;
            // the adpcm of vavi-sound's engines is mixed into this line, in step with the notes
            mixing = AudioEngineMixer.attach();
            Thread renderer = new Thread(this::run, "UCS fuetrek renderer");
            renderer.setDaemon(true);
            renderer.setPriority(Thread.MAX_PRIORITY);
            renderer.start();
logger.log(Level.DEBUG, "line: " + line.getFormat() + ", buffer: " + line.getBufferSize());
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("cannot open UCS output", e);
        }
    }

    private void run() {
        byte[] pcm = new byte[BLOCK * 4];
        // what goes to the line, as raw pcm (32 kHz, 16 bit, stereo, little endian), for comparing
        String dump = System.getProperty("vavi.sound.fuetrek.dump");
        try (java.io.OutputStream out = dump == null ? java.io.OutputStream.nullOutputStream()
                : new java.io.BufferedOutputStream(new java.io.FileOutputStream(dump))) {
            short[] mix = new short[BLOCK * 2];
            while (running) {
                render(pcm, BLOCK);
                if (mixing) {
                    mixAdpcm(pcm, mix);
                }
                SourceDataLine line = this.line;
                if (line == null) break;
                line.write(pcm, 0, pcm.length);
                out.write(pcm);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "dump: " + e);
        }
    }

    /** whether the adpcm is mixed into the line, see {@link AudioEngineMixer#attach()} */
    private volatile boolean mixing;

    /** adds the adpcm of vavi-sound's engines to a block rendered for the line */
    private static void mixAdpcm(byte[] pcm, short[] mix) {
        int frames = pcm.length / 4;
        for (int i = 0; i < frames * 2; i++) {
            mix[i] = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
        }
        AudioEngineMixer.render(mix, 0, frames, SAMPLE_RATE);
        for (int i = 0; i < frames * 2; i++) {
            pcm[i * 2] = (byte) mix[i];
            pcm[i * 2 + 1] = (byte) (mix[i] >> 8);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (mixing) {
            mixing = false;
            AudioEngineMixer.detach();
        }
        synchronized (lock) {
            java.util.Arrays.fill(voices, null);
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }
}
