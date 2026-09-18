/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.rohm;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mfi.vavi.sequencer.MfiValueExclusive;
import vavi.sound.mobile.AudioEngineMixer;

import static java.lang.System.getLogger;


/**
 * The rohm sound source ({@link RohmSoundSource}) played, into a line of its own or rendered
 * by the one who asks.
 * <p>
 * The midi goes to the sound source as it comes. The mfi values vavi sends along with the midi
 * it converts mfi into ({@link MfiValueExclusive}) are taken as the fuetrek sound source takes
 * them, the bank of the rohm one being the same groups:
 * <ul>
 * <li>the bank ... 0: the group 0x7d, 1 ~ 0x33: the melody group 0x79 (odd banks + 0x40),
 *     0x36: the group 0x11, a drum channel 0x34: the second drum set 0x14</li>
 * <li>the master volume ... the song's, the universal master volume following is it and goes to
 *     the sound source, any other is the listener's and is a gain after it</li>
 * </ul>
 * system property
 * <li>{@code vavi.sound.mfi.rohm.dump} ... a file what is played is written to too, raw pcm 44.1 kHz 16 bit stereo little endian</li>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public final class RohmAudioEngine implements AutoCloseable {

    private static final Logger logger = getLogger(RohmAudioEngine.class.getName());

    public static final int SAMPLE_RATE = RohmSoundSource.SAMPLE_RATE;
    private static final int BLOCK = RohmSoundSource.BLOCK;
    private static final int DRUM_CHANNEL = 9;

    private final RohmSoundSource source;

    /** mfi bank, -1: not told */
    private final int[] bank = new int[RohmSoundSource.CHANNELS];
    /** the midi program */
    private final int[] program = new int[RohmSoundSource.CHANNELS];
    /** bank select msb as it came, 0: the default of the channel */
    private final int[] bankSelect = new int[RohmSoundSource.CHANNELS];
    /** the next universal master volume is the song's */
    private boolean songVolume;
    /** the listener's */
    private volatile double gain = 1;

    private final Object lock = new Object();
    /** false: {@link #render(byte[], int)} is called by the user, no line is opened */
    private final boolean realtime;
    private volatile boolean running;
    private SourceDataLine line;

    /** a block rendered and not all taken yet */
    private final int[] block = new int[BLOCK * 2];
    private int blockPosition = BLOCK;

    public RohmAudioEngine() throws IOException {
        this(RohmRom.getInstance(), true);
    }

    /** @param realtime false: renders only by {@link #render(byte[], int)} */
    public RohmAudioEngine(RohmRom rom, boolean realtime) {
        this.source = new RohmSoundSource(rom);
        this.realtime = realtime;
        Arrays.fill(bank, -1);
    }

    /** the sound source, {@link #lock} it */
    RohmSoundSource source() {
        return source;
    }

    // ---- midi

    /**
     * @param status a channel message
     */
    public void shortMessage(int status, int data1, int data2) {
        synchronized (lock) {
            int c = status & 0x0f;
            switch (status & 0xf0) {
            case 0xb0 -> {
                if (data1 == 0) bankSelect[c] = data2 & 0x7f;
                source.shortMessage(status, data1, data2);
            }
            case 0xc0 -> {
                program[c] = data1 & 0x7f;
                programChange(c);
            }
            default -> source.shortMessage(status, data1, data2);
            }
        }
        if ((status & 0xf0) == 0x90) ensureStarted();
    }

    /** a program change, of the mfi bank when it is told */
    private void programChange(int c) {
        if (bank[c] < 0) {
            source.shortMessage(0xc0 | c, program[c], 0);
            return;
        }
        int group, index = program[c] & 0x3f;
        boolean drum = bankSelect[c] != 0 ? (bankSelect[c] & 1) == 0 : c == DRUM_CHANNEL;
        if (drum) {
            group = bank[c] == 0x34 ? 0x14 : 0x78;
            index = program[c];
        } else if (bank[c] == 0) {
            group = 0x7d;
        } else if (bank[c] == 0x36) {
            group = 0x11;
        } else if (bank[c] < 0x34) {
            group = 0x79;
            index += (bank[c] & 1) != 0 ? 0x40 : 0;
        } else {
            // nothing sounds
            group = 0x7d;
            index = 0x7f;
        }
        source.shortMessage(0xb0 | c, 0, group);
        source.shortMessage(0xc0 | c, index, 0);
        source.shortMessage(0xb0 | c, 0, bankSelect[c]);
    }

    /**
     * @param data an exclusive, f0 ... f7
     * @return false: not taken, the adpcm of vavi goes on elsewhere
     */
    public boolean exclusive(byte[] data) {
        synchronized (lock) {
            switch (MfiValueExclusive.sub(data)) {
            case MfiValueExclusive.BANK -> {
                if (data.length >= 7) {
                    int c = data[4] & 0x0f;
                    bank[c] = data[5] & 0x3f;
                    programChange(c);
                }
                return true;
            }
            case MfiValueExclusive.MASTER_VOLUME -> {
                songVolume = true;
                return true;
            }
            case -1 -> {}
            default -> {
                return true;
            }
            }
            // universal master volume: the song's after the mfi one, the listener's otherwise
            if (data.length >= 7 && (data[0] & 0xff) == 0xf0 && data[1] == 0x7f && data[3] == 0x04 && data[4] == 0x01) {
                if (songVolume) {
                    songVolume = false;
                } else {
                    gain = ((data[5] & 0x7f) | ((data[6] & 0x7f) << 7)) / 16383d;
                    return true;
                }
            }
            // gm system on: the mfi banks go too
            if (data.length >= 5 && (data[0] & 0xff) == 0xf0 && data[1] == 0x7e && data[3] == 0x09 && data[4] == 0x01) {
                Arrays.fill(bank, -1);
                Arrays.fill(bankSelect, 0);
            }
            return source.exclusive(data);
        }
    }

    /** @param preset 0 ~ 7, -1: off */
    public void reverb(int preset) {
        synchronized (lock) {
            source.reverb(preset);
        }
    }

    /** @param gain the listener's, 0 ~ 1 */
    public void gain(double gain) {
        this.gain = gain;
    }

    /** all notes off at once, the channels stay */
    public void reset() {
        synchronized (lock) {
            source.reset();
        }
    }

    // ---- audio

    /**
     * @param pcm 16 bit little endian stereo
     * @param frames frames to render
     */
    public void render(byte[] pcm, int frames) {
        synchronized (lock) {
            double gain = this.gain;
            for (int f = 0; f < frames; f++) {
                if (blockPosition == BLOCK) {
                    source.render(block);
                    blockPosition = 0;
                }
                int l = block[blockPosition * 2], r = block[blockPosition * 2 + 1];
                blockPosition++;
                if (gain != 1) {
                    l = (int) (l * gain);
                    r = (int) (r * gain);
                }
                l = Math.clamp(l, -0x8000, 0x7fff);
                r = Math.clamp(r, -0x8000, 0x7fff);
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
    void startOutput() {
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
            Thread renderer = new Thread(this::run, "rohm renderer");
            renderer.setDaemon(true);
            renderer.setPriority(Thread.MAX_PRIORITY);
            renderer.start();
logger.log(Level.DEBUG, "line: " + line.getFormat() + ", buffer: " + line.getBufferSize());
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("cannot open the rohm output", e);
        }
    }

    private void run() {
        byte[] pcm = new byte[BLOCK * 4];
        // what goes to the line, as raw pcm (44.1 kHz, 16 bit, stereo, little endian), for comparing
        String dump = System.getProperty("vavi.sound.mfi.rohm.dump");
        try (OutputStream out = dump == null ? OutputStream.nullOutputStream()
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
            source.reset();
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }
}
