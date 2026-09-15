/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import static java.lang.System.getLogger;


/**
 * The fuetrek sound source in pure java: the preset tones of {@link FuetrekRom}
 * and the UCS user waves of {@link UcsSequencer}, played by midi channel messages.
 * <p>
 * The midi is the one vavi converts mfi into, so the values are the mfi ones doubled
 * and the keys are already the note bytes of the sound source.
 * <ul>
 * <li>program ... the melody group, 0 ~ 63 of bank 2, 64 ~ 127 of bank 3</li>
 * <li>channel 9 ... the drum group, by key</li>
 * <li>a program a UCS wave is assigned to ... the UCS wave</li>
 * </ul>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
public final class UcsAudioEngine implements AutoCloseable {

    private static final Logger logger = getLogger(UcsAudioEngine.class.getName());

    /** the native rate, the rom steps are for it */
    public static final int SAMPLE_RATE = 32_000;
    /** the control block */
    private static final int BLOCK = 128;
    private static final int POLYPHONY = 32;
    private static final int CHANNEL_DRUM = 9;

    /** global mix state */
    static final class Mix {
        /** host master volume, gain byte */
        int host = 0x7f;
        /** mfi master volume */
        int master = 0x7f;

        int globalVolume() {
            return Math.clamp(host, 0, 0x7f) * Math.clamp(master, 0, 0x7f) / 0x7f;
        }
    }

    /** a midi channel */
    static final class Channel {
        final int index;
        final Mix mix;
        int program;
        int volume = 0x64;
        int expression = 0x7f;
        /** signed -64 ~ 63 */
        int pan;
        /** 14 bit */
        int bend = 0x2000;
        int bendRange = 2;
        int modulation;
        int rpn = 0x7f7f;

        Channel(int index, Mix mix) {
            this.index = index;
            this.mix = mix;
        }

        boolean isDrum() {
            return index == CHANNEL_DRUM;
        }

        int modulation() {
            return Math.clamp(modulation, 0, 0x7f);
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
        release(c.index, key);
        List<UcsSequencer.Wave> waves = UcsSequencer.waveBank().tone(c.program);
        if (!waves.isEmpty()) {
            return ucs(c, key, note, velocity, waves);
        }
        FuetrekRom.Instrument instrument = rom.instrument(FuetrekRom.GROUP_MELODY, c.program);
        FuetrekRom.Zone zone = instrument == null ? null : instrument.zone(note);
        if (zone == null || zone.sampleA == null) return null;
        return new FuetrekVoice(rom, c, key, note, velocity, FuetrekRom.GROUP_MELODY, c.program,
                zone.sampleA, zone.sampleB, zone.s8(0x10), zone.s16(0x12), zone.s32(0x3c), FuetrekVoice.Template.of(zone), age++);
    }

    private FuetrekVoice drum(Channel c, int key, int note, int velocity) {
        FuetrekRom.Instrument instrument = rom.instrument(FuetrekRom.GROUP_DRUM, note);
        FuetrekRom.Zone zone = instrument == null ? null : instrument.zone(note);
        if (zone == null || zone.sampleA == null) return null;
        // the same drum note stops at once
        for (int i = 0; i < voices.length; i++) {
            if (voices[i] != null && voices[i].channel == c && voices[i].group == FuetrekRom.GROUP_DRUM && voices[i].note == note) {
                voices[i] = null;
            }
        }
        return new FuetrekVoice(rom, c, key, note, velocity, FuetrekRom.GROUP_DRUM, note,
                zone.sampleA, zone.sampleB, zone.s8(0x10), zone.s16(0x12), zone.s32(0x3c), FuetrekVoice.Template.of(zone), age++);
    }

    /** the wave whose root key is the nearest, with the voice parameters of its own */
    private FuetrekVoice ucs(Channel c, int key, int note, int velocity, List<UcsSequencer.Wave> waves) {
        UcsSequencer.Wave wave = waves.getFirst();
        for (UcsSequencer.Wave candidate : waves) {
            if (Math.abs(note - candidate.rootPitch) < Math.abs(note - wave.rootPitch)) wave = candidate;
        }
        byte[] parameters = wave.parameters != null ? wave.parameters : new byte[0];
        FuetrekVoice.Template template = parameters.length >= 44 ? FuetrekVoice.Template.of(rom, parameters) : plain();
        int tune = parameters.length >= 8 ? rom.rootKeyTune(parameters[7] & 0xff) : 0;
        FuetrekRom.Sample sample = new FuetrekRom.Sample("ucs", wave.pcm(), wave.loopStart, wave.loopEnd,
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
            release(channel, key);
        }
    }

    private void release(int channel, int key) {
        for (FuetrekVoice voice : voices) {
            if (voice != null && voice.channel.index == channel && voice.key == key && !voice.channel.isDrum()) {
                voice.release();
            }
        }
    }

    public void programChange(int channel, int program) {
        synchronized (lock) {
            channels[channel].program = program & 0x7f;
        }
    }

    public void controlChange(int channel, int control, int value) {
        synchronized (lock) {
            Channel c = channels[channel];
            switch (control) {
            case 1 -> {
                c.modulation = value;
                forEach(c, v -> v.modulation(c.modulation()));
            }
            case 7 -> c.volume = value;
            case 10 -> c.pan = Math.clamp(value - 0x40, -0x40, 0x3f);
            // vavi puts mfi's relative volume here, signed, doubled
            case 11 -> c.volume = Math.clamp(c.volume + ((value >= 0x40 ? value - 0x80 : value) & ~1), 0, 0x7e);
            case 100 -> c.rpn = (c.rpn & 0x7f00) | value;
            case 101 -> c.rpn = (c.rpn & 0x7f) | (value << 8);
            case 6 -> {
                if (c.rpn == 0) {
                    c.bendRange = Math.clamp(value, 0, 0x18);
                    forEach(c, v -> v.bendRange(c.bendRange, c.bend));
                }
            }
            case 120 -> forEach(c, v -> {
                for (int i = 0; i < voices.length; i++) if (voices[i] == v) voices[i] = null;
            });
            case 123 -> forEach(c, FuetrekVoice::release);
            default -> {}
            }
        }
    }

    /** @param value 14 bit */
    public void pitchBend(int channel, int value) {
        synchronized (lock) {
            Channel c = channels[channel];
            c.bend = Math.clamp(value, 0, 0x3fff);
            forEach(c, v -> v.bend(c.bend));
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
            for (int f = 0; f < frames; f++) {
                int left = 0, right = 0;
                for (int i = 0; i < voices.length; i++) {
                    FuetrekVoice voice = voices[i];
                    if (voice == null) continue;
                    if (voice.render(counter)) {
                        left += voice.left;
                        right += voice.right;
                    } else {
                        voices[i] = null;
                    }
                }
                counter = (counter + 1) & (BLOCK - 1);
                left = FuetrekVoice.clamp16(left);
                right = FuetrekVoice.clamp16(right);
                pcm[f * 4] = (byte) left;
                pcm[f * 4 + 1] = (byte) (left >> 8);
                pcm[f * 4 + 2] = (byte) right;
                pcm[f * 4 + 3] = (byte) (right >> 8);
            }
        }
    }

    private synchronized void ensureStarted() {
        if (running || !realtime) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BLOCK * 4 * 8);
            line.start();
            running = true;
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
        while (running) {
            render(pcm, BLOCK);
            SourceDataLine line = this.line;
            if (line == null) break;
            line.write(pcm, 0, pcm.length);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
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
