//
// Copyright (C) 2015-2016 Alexey Khokholov (Nuke.YKT)
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//

package vavi.sound.midi.ymf262;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;

import mdsound.chips.NukedYmF262;

import static java.lang.Math.pow;
import static java.lang.System.getLogger;


/**
 * NukedSynthesizer.
 *
 * @author Alexey Khokholov (Nuke.YKT)
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/03/12 umjammer initial version <br>
 * @see "https://github.com/nukeykt/WinOPL3Driver/blob/master/opl3windows/driver/synthlib/opl3midi.cpp"
 */
public class NukedPlayer {

    private static final Logger logger = getLogger(NukedPlayer.class.getName());

    private static final int OPL_LSI = 0x00;
    private static final int OPL_TIMER = 0x04;
    private static final int OPL_4OP = 0x104;
    private static final int OPL_NEW = 0x105;
    private static final int OPL_NTS = 0x08;
    private static final int OPL_MULT = 0x20;
    private static final int OPL_TL = 0x40;
    private static final int OPL_AD = 0x60;
    private static final int OPL_SR = 0x80;
    private static final int OPL_WAVE = 0xe0;
    private static final int OPL_FNUM = 0xa0;
    private static final int OPL_BLOCK = 0xb0;
    private static final int OPL_RHYTHM = 0xbd;
    private static final int OPL_FEEDBACK = 0xc0;

    private static final int MIDI_DRUMCHANNEL = 9;
    private static final int MIDI_NOTEOFF = 0x80;
    private static final int MIDI_NOTEON = 0x90;
    private static final int MIDI_CONTROL = 0xb0;
    private static final int MIDI_CONTROL_VOL = 0x07;
    private static final int MIDI_CONTROL_BAL = 0x08;
    private static final int MIDI_CONTROL_PAN = 0x0a;
    private static final int MIDI_CONTROL_SUS = 0x40;
    private static final int MIDI_CONTROL_ALLOFF = 0x78;
    private static final int MIDI_PROGRAM = 0xc0;
    private static final int MIDI_PITCHBEND = 0xe0;

    private static final double opl_samplerate = 50000.0;
    private static final double opl_tune = 440.0;
    private static final byte opl_pitchfrac = 8;

    private static final byte[] opl_volume_map = {
            80, 63, 40, 36, 32, 28, 23, 21,
            19, 17, 15, 14, 13, 12, 11, 10,
            9, 8, 7, 6, 5, 5, 4, 4,
            3, 3, 2, 2, 1, 1, 0, 0
    };

    private static final byte[] opl_voice_map = {
            0, 1, 2, 8, 9, 10, 16, 17, 18
    };

    public static class opl_timbre {

        int[] mult = new int[2];
        int[] tl = new int[2];
        int[] ad = new int[2];
        int[] sr = new int[2];
        int[] wf = new int[2];
        int fb;
        int note;
        int octave;

        public opl_timbre(int[] seed) {
            this.mult[0] = seed[0];
            this.mult[1] = seed[1];
            this.tl[0] = seed[2];
            this.tl[1] = seed[3];
            this.ad[0] = seed[4];
            this.ad[1] = seed[5];
            this.sr[0] = seed[6];
            this.sr[1] = seed[7];
            this.wf[0] = seed[8];
            this.wf[1] = seed[9];
            fb = seed[10];
            note = seed[11];
            octave = seed[12];
        }
    }

    public static class opl_drum_map {

        public int base;
        public int note;

        public opl_drum_map(int[] seed) {
            this.base = seed[0];
            this.note = seed[1];
        }
    }

    private static class opl_channel {

        opl_timbre timbre;
        int pitch;
        int volume;
        int pan;
        boolean sustained;
    }

    private static class opl_voice {

        int num;
        int mod, car;
        int freq;
        int freqpitched;
        int time;
        int note;
        int velocity;
        boolean keyon;
        boolean sustained;
        opl_timbre timbre;
        opl_channel channel;
    }

    private static final opl_timbre[] opl_timbres;
    private static final opl_drum_map[] opl_drum_maps;

    private final NukedYmF262 opl = new NukedYmF262();
    private NukedYmF262.Chip opl_chip;

    private boolean opl_opl3mode;

    private int opl_voice_num;

    private final opl_channel[] opl_channels = new opl_channel[16];
    private final opl_voice[] opl_voices = new opl_voice[18];

    private final int[] opl_freq = new int[12];
    private int opl_time;
    private int opl_uppitch;
    private int opl_downpitch;

    {
        Arrays.setAll(opl_channels, i -> new opl_channel());
        Arrays.setAll(opl_voices, i -> new opl_voice());
    }

    public static opl_timbre[] getInstruments() {
        return opl_timbres;
    }

    public static opl_drum_map[] getDrumMaps() {
        return opl_drum_maps;
    }

    private void opl_writereg(int reg, int data) {
        opl.OPL3_WriteReg(opl_chip, reg, data);
    }

    private int opl_tofnum(double freq) {
        return (int) (((1 << 19) * freq) / opl_samplerate);
    }

    private void opl_buildfreqtable() {
        double opl_semitone = pow(2.0, 1.0 / 12.0);
        opl_freq[0] = opl_tofnum(opl_tune * pow(opl_semitone, -9));
        opl_freq[1] = opl_tofnum(opl_tune * pow(opl_semitone, -8));
        opl_freq[2] = opl_tofnum(opl_tune * pow(opl_semitone, -7));
        opl_freq[3] = opl_tofnum(opl_tune * pow(opl_semitone, -6));
        opl_freq[4] = opl_tofnum(opl_tune * pow(opl_semitone, -5));
        opl_freq[5] = opl_tofnum(opl_tune * pow(opl_semitone, -4));
        opl_freq[6] = opl_tofnum(opl_tune * pow(opl_semitone, -3));
        opl_freq[7] = opl_tofnum(opl_tune * pow(opl_semitone, -2));
        opl_freq[8] = opl_tofnum(opl_tune * pow(opl_semitone, -1));
        opl_freq[9] = opl_tofnum(opl_tune * pow(opl_semitone, 0));
        opl_freq[10] = opl_tofnum(opl_tune * pow(opl_semitone, 1));
        opl_freq[11] = opl_tofnum(opl_tune * pow(opl_semitone, 2));

        opl_uppitch = (int) ((opl_semitone * opl_semitone - 1.0) * (1 << opl_pitchfrac));
        opl_downpitch = (int) ((1.0 - 1.0 / (opl_semitone * opl_semitone)) * (1 << opl_pitchfrac));
    }

    private int opl_calcblock(int freq) {
        byte block = 1;
        while (freq > 0x3ff) {
            block++;
            freq /= 2;
        }
        if (block > 0x07) {
            block = 0x07;
        }

        return (block << 10) | freq;
    }

    private int opl_applypitch(int freq, int pitch) {
        int diff;

        if (pitch > 0) {
            diff = (pitch * opl_uppitch) >> opl_pitchfrac;
            freq += (diff * freq) >> 15;
        } else if (pitch < 0) {
            diff = (-pitch * opl_downpitch) >> opl_pitchfrac;
            freq -= (diff * freq) >> 15;
        }
        return freq;
    }

    private opl_voice opl_allocvoice(opl_timbre timbre) {
        int time;
        int id;

        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].time == 0) {
                return opl_voices[i];
            }
        }

        time = Integer.MAX_VALUE;
        id = -1;

        for (int i = 0; i < opl_voice_num; i++) {
            if (!opl_voices[i].keyon && opl_voices[i].time < time) {
                id = i;
                time = opl_voices[i].time;
            }
        }
        if (id >= 0) {
            return opl_voices[id];
        }

        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].timbre == timbre && opl_voices[i].time < time) {
                id = i;
                time = opl_voices[i].time;
            }
        }
        if (id >= 0) {
            return opl_voices[id];
        }

        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].time < time) {
                id = i;
                time = opl_voices[i].time;
            }
        }

        return opl_voices[id];
    }

    private opl_voice opl_findvoice(opl_channel channel, int note) {
        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].keyon && opl_voices[i].channel == channel && opl_voices[i].note == note) {
                return opl_voices[i];
            }
        }
        return null;
    }

    private void opl_midikeyon(opl_channel channel, int note, opl_timbre timbre, int velocity) {
        opl_voice voice;
        int freq;
        int freqpitched;
        int octave;
        int carvol;
        int modvol;
        int fb;

        octave = note / 12;
        freq = opl_freq[note % 12];
        if (octave < 5) {
            freq >>= (5 - octave);
        } else if (octave > 5) {
            freq <<= (octave - 5);
        }

        if (timbre.octave < 4) {
            freq >>= (4 - timbre.octave);
        } else if (timbre.octave > 4) {
            freq >>= (timbre.octave - 4);
        }

        freqpitched = opl_calcblock(opl_applypitch(freq, channel.pitch));

        carvol = (timbre.tl[1] & 0x3f) + channel.volume + opl_volume_map[velocity >> 2];
        modvol = timbre.tl[0] & 0x3f;

        if ((timbre.fb & 0x01) != 0) {
            modvol += channel.volume + opl_volume_map[velocity >> 2];
        }

        if (carvol > 0x3f) {
            carvol = 0x3f;
        }

        if (modvol > 0x3f) {
            modvol = 0x3f;
        }

        carvol |= (timbre.tl[1] & 0xc0);
        modvol |= (timbre.tl[0] & 0xc0);

        fb = timbre.fb & channel.pan;

        voice = opl_allocvoice(timbre);

        opl_writereg(OPL_BLOCK + voice.num, 0x00);

        opl_writereg(OPL_MULT + voice.mod, timbre.mult[0]);
        opl_writereg(OPL_TL + voice.mod, modvol);
        opl_writereg(OPL_AD + voice.mod, timbre.ad[0]);
        opl_writereg(OPL_SR + voice.mod, timbre.sr[0]);
        opl_writereg(OPL_WAVE + voice.mod, timbre.wf[0]);

        opl_writereg(OPL_MULT + voice.car, timbre.mult[1]);
        opl_writereg(OPL_TL + voice.car, carvol);
        opl_writereg(OPL_AD + voice.car, timbre.ad[1]);
        opl_writereg(OPL_SR + voice.car, timbre.sr[1]);
        opl_writereg(OPL_WAVE + voice.car, timbre.wf[1]);

        opl_writereg(OPL_FNUM + voice.num, freqpitched & 0xff);
        opl_writereg(OPL_FEEDBACK + voice.num, fb);
        opl_writereg(OPL_BLOCK + voice.num, (freqpitched >> 8) | 0x20);

        voice.freq = freq;
        voice.freqpitched = freqpitched;
        voice.note = note;
        voice.velocity = velocity;
        voice.timbre = timbre;
        voice.channel = channel;
        voice.time = opl_time++;
        voice.keyon = true;
        voice.sustained = false;
    }

    private void opl_midikeyoff(opl_channel channel, int note, opl_timbre timbre, boolean sustained) {

        opl_voice voice = opl_findvoice(channel, note);
        if (voice == null) {
            return;
        }

        if (sustained) {
            voice.sustained = true;
            return;
        }

        opl_writereg(OPL_BLOCK + voice.num, voice.freqpitched >> 8);

        voice.keyon = false;
        voice.time = opl_time;
    }

    private void opl_midikeyoffall(opl_channel channel) {
        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].channel == channel) {
                opl_midikeyoff(opl_voices[i].channel, opl_voices[i].note, opl_voices[i].timbre, false);
            }
        }
    }

    private void opl_updatevolpan(opl_channel channel) {
        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].channel == channel) {
                int carvol = (opl_voices[i].timbre.tl[1] & 0x3f) + channel.volume + opl_volume_map[opl_voices[i].velocity >> 2];
                int modvol = opl_voices[i].timbre.tl[0] & 0x3f;

                if ((opl_voices[i].timbre.fb & 0x01) != 0) {
                    modvol += channel.volume + opl_volume_map[opl_voices[i].velocity >> 2];
                }

                if (carvol > 0x3f) {
                    carvol = 0x3f;
                }

                if (modvol > 0x3f) {
                    modvol = 0x3f;
                }

                carvol |= (opl_voices[i].timbre.tl[1] & 0xc0);
                modvol |= (opl_voices[i].timbre.tl[0] & 0xc0);

                opl_writereg(OPL_TL + opl_voices[i].mod, modvol);
                opl_writereg(OPL_TL + opl_voices[i].car, carvol);

                opl_writereg(OPL_FEEDBACK + opl_voices[i].num, opl_voices[i].timbre.fb & channel.pan);
            }
        }
    }

    private void opl_updatevol(opl_channel channel, int vol) {
        channel.volume = opl_volume_map[vol >> 2];
        opl_updatevolpan(channel);
    }

    private void opl_updatepan(opl_channel channel, int pan) {
        if (pan < 48) {
            channel.pan = 0xdf;
        } else if (pan > 80) {
            channel.pan = 0xef;
        } else {
            channel.pan = 0xff;
        }
        opl_updatevolpan(channel);
    }

    private void opl_updatesustain(opl_channel channel, int sustain) {
        if (sustain >= 64) {
            channel.sustained = true;
        } else {
            channel.sustained = false;

            for (int i = 0; i < opl_voice_num; i++) {
                if (opl_voices[i].channel == channel && opl_voices[i].sustained) {
                    opl_midikeyoff(channel, opl_voices[i].note, opl_voices[i].timbre, false);
                }
            }
        }
    }

    private void opl_updatepitch(opl_channel channel) {
        for (int i = 0; i < opl_voice_num; i++) {
            if (opl_voices[i].channel == channel) {
                int freqpitch = opl_calcblock(opl_applypitch(opl_voices[i].freq, channel.pitch));
                opl_voices[i].freqpitched = freqpitch;

                opl_writereg(OPL_BLOCK + opl_voices[i].num, (freqpitch >> 8) | ((opl_voices[i].keyon ? 1 : 0) << 5));
                opl_writereg(OPL_FNUM + opl_voices[i].num, freqpitch & 0xff);
            }
        }
    }

    private void opl_midicontrol(opl_channel channel, int type, int data) {
        switch (type) {
            case MIDI_CONTROL_VOL:
                opl_updatevol(channel, data);
                break;
            case MIDI_CONTROL_BAL:
            case MIDI_CONTROL_PAN:
                opl_updatepan(channel, data);
                break;
            case MIDI_CONTROL_SUS:
                opl_updatesustain(channel, data);
                break;
            default:
                if (type >= MIDI_CONTROL_ALLOFF) {
                    opl_midikeyoffall(channel);
                }
        }
    }

    private void opl_midiprogram(opl_channel channel, int program) {
        if (channel != opl_channels[MIDI_DRUMCHANNEL]) {
            channel.timbre = opl_timbres[program];
        }
    }

    private void opl_midipitchbend(opl_channel channel, int parm1, int parm2) {

        int pitch = (parm2 << 9) | (parm1 << 2);
        pitch += 0x7fff;
        channel.pitch = pitch;

        opl_updatepitch(channel);
    }

    public int midi_init(int rate) {

        opl_chip = new NukedYmF262.Chip();
        opl.OPL3_Reset(opl_chip, rate);

        opl_opl3mode = true;

        opl_writereg(OPL_LSI, 0x00);
        opl_writereg(OPL_TIMER, 0x60);
        opl_writereg(OPL_NTS, 0x00);
        if (opl_opl3mode) {
            opl_writereg(OPL_NEW, 0x01);
            opl_writereg(OPL_4OP, 0x00);
        }
        opl_writereg(OPL_RHYTHM, 0xc0);

        for (int i = 0; i <= 0x15; i++) {
            opl_writereg(OPL_TL + i, 0x3f);
            if (opl_opl3mode) {
                opl_writereg(OPL_TL + 0x100 + i, 0x3f);
            }
        }

        for (int i = 0; i < 9; i++) {
            opl_writereg(OPL_BLOCK + i, 0x00);
            if (opl_opl3mode) {
                opl_writereg(OPL_BLOCK + 0x100 + i, 0x00);
            }
        }

        opl_voice_num = 9;

        if (opl_opl3mode) {
            opl_voice_num = 18;
        }

        for (int i = 0; i < opl_voice_num; i++) {
            opl_voices[i].num = i % 9;
            opl_voices[i].mod = opl_voice_map[i % 9];
            opl_voices[i].car = opl_voice_map[i % 9] + 3;
            if (i >= 9) {
                opl_voices[i].num += 0x100;
                opl_voices[i].mod += 0x100;
                opl_voices[i].car += 0x100;
            }
            opl_voices[i].freq = 0;
            opl_voices[i].freqpitched = 0;
            opl_voices[i].time = 0;
            opl_voices[i].note = 0;
            opl_voices[i].velocity = 0;
            opl_voices[i].keyon = false;
            opl_voices[i].sustained = false;
            opl_voices[i].timbre = opl_timbres[0];
            opl_voices[i].channel = opl_channels[0];
        }

        for (int i = 0; i < 16; i++) {
            opl_channels[i].timbre = opl_timbres[0];
            opl_channels[i].pitch = 0;
            opl_channels[i].volume = 0;
            opl_channels[i].pan = 0xff;
            opl_channels[i].sustained = false;
        }

        opl_buildfreqtable();

        opl_time = 1;

        return 1;
    }

    public void midi_write(int data) {
        int event_type = data & 0xf0;
        int channel = data & 0x0f;
        int parm1 = (data >> 8) & 0x7f;
        int parm2 = (data >> 16) & 0x7f;
        midi_write(event_type, channel, parm1, parm2);
    }

    public void midi_write(int event_type, int channel, int parm1, int parm2) {
logger.log(Level.TRACE, "ev: %d, ch: %d, p1: %d, p2: %d".formatted(event_type, channel, parm1, parm2));
        opl_channel channelp = opl_channels[channel];

        switch (event_type) {
            case MIDI_NOTEON:
                if (parm2 > 0) {
                    if (channel == MIDI_DRUMCHANNEL) {
                        if (opl_drum_maps[parm1].base != 0xff) {
                            opl_midikeyon(channelp, opl_drum_maps[parm1].note, opl_timbres[opl_drum_maps[parm1].base + 128], parm2);
                        }
                    } else {
                        opl_midikeyon(channelp, parm1, channelp.timbre, parm2);
                    }
                    break;
                }
            case MIDI_NOTEOFF:
                if (channel == MIDI_DRUMCHANNEL) {
                    if (opl_drum_maps[parm1].base != 0xff) {
                        opl_midikeyoff(channelp, opl_drum_maps[parm1].note, opl_timbres[opl_drum_maps[parm1].base + 128], false);
                    }
                } else {
                    opl_midikeyoff(channelp, parm1, channelp.timbre, channelp.sustained);
                }
                break;
            case MIDI_CONTROL:
                opl_midicontrol(channelp, parm1, parm2);
                break;
            case MIDI_PROGRAM:
                opl_midiprogram(channelp, parm1);
                break;
            case MIDI_PITCHBEND:
                opl_midipitchbend(channelp, parm1, parm2);
                break;
        }
    }

    public void midi_panic() {
        for (int c = 0; c < 16; ++c)
            opl_midikeyoffall(opl_channels[c]);
    }

    public void midi_reset() {
        midi_panic();

        for (int i = 0; i < 16; i++) {
            opl_channels[i].timbre = opl_timbres[0];
            opl_channels[i].pitch = 0;
            opl_channels[i].volume = 0;
            opl_channels[i].pan = 0xff;
            opl_channels[i].sustained = false;
        }
    }

    public void midi_generate(int[][] buffer, int length) {
        short[] b = new short[4];
        for (int i = 0; i < length; i++) {
            opl.OPL3_GenerateResampled(opl_chip, b, 0);
            buffer[0][i] = b[0];
            buffer[1][i] = b[1];
        }
    }

    // ---- TODO out source

    private static final int[][] opl_timbres_ = {
        { 1, 1, 143, 6, 242, 242, 244, 247, 0, 0, 56, 0, 4 }, // 0
        { 1, 1, 75, 0, 242, 242, 244, 247, 0, 0, 56, 0, 4 },
        { 1, 1, 73, 0, 242, 242, 244, 246, 0, 0, 56, 0, 4 },
        { 129, 65, 18, 0, 242, 242, 247, 247, 0, 0, 54, 0, 4 },
        { 1, 1, 87, 0, 241, 242, 247, 247, 0, 0, 48, 0, 4 },
        { 1, 1, 147, 0, 241, 242, 247, 247, 0, 0, 48, 0, 4 },
        { 1, 22, 128, 14, 161, 242, 242, 245, 0, 0, 56, 0, 4 },
        { 1, 1, 146, 0, 194, 194, 248, 248, 0, 0, 58, 0, 4 },
        { 12, 129, 92, 0, 246, 243, 244, 245, 0, 0, 48, 0, 4 },
        { 7, 17, 151, 128, 243, 242, 242, 241, 0, 0, 50, 0, 4 },
        { 23, 1, 33, 0, 84, 244, 244, 244, 0, 0, 50, 0, 4 }, // 10
        { 152, 129, 98, 0, 243, 242, 246, 246, 0, 0, 48, 0, 4 },
        { 24, 1, 35, 0, 246, 231, 246, 247, 0, 0, 48, 0, 4 },
        { 21, 1, 145, 0, 246, 246, 246, 246, 0, 0, 52, 0, 4 },
        { 69, 129, 89, 128, 211, 163, 243, 243, 0, 0, 60, 0, 4 },
        { 3, 129, 73, 128, 117, 181, 245, 245, 1, 0, 52, 0, 4 },
        { 113, 49, 146, 0, 246, 241, 20, 7, 0, 0, 50, 0, 4 },
        { 114, 48, 20, 0, 199, 199, 88, 8, 0, 0, 50, 0, 4 },
        { 112, 177, 68, 0, 170, 138, 24, 8, 0, 0, 52, 0, 4 },
        { 35, 177, 147, 0, 151, 85, 35, 20, 1, 0, 52, 0, 4 },
        { 97, 177, 19, 128, 151, 85, 4, 4, 1, 0, 48, 0, 4 }, // 20
        { 36, 177, 72, 0, 152, 70, 42, 26, 1, 0, 60, 0, 4 },
        { 97, 33, 19, 0, 145, 97, 6, 7, 1, 0, 58, 0, 4 },
        { 33, 161, 19, 137, 113, 97, 6, 7, 0, 0, 54, 0, 4 },
        { 2, 65, 156, 128, 243, 243, 148, 200, 1, 0, 60, 0, 4 },
        { 3, 17, 84, 0, 243, 241, 154, 231, 1, 0, 60, 0, 4 },
        { 35, 33, 95, 0, 241, 242, 58, 248, 0, 0, 48, 0, 4 },
        { 3, 33, 135, 128, 246, 243, 34, 248, 1, 0, 54, 0, 4 },
        { 3, 33, 71, 0, 249, 246, 84, 58, 0, 0, 48, 0, 4 },
        { 35, 33, 74, 5, 145, 132, 65, 25, 1, 0, 56, 0, 4 },
        { 35, 33, 74, 0, 149, 148, 25, 25, 1, 0, 56, 0, 4 }, // 30
        { 9, 132, 161, 128, 32, 209, 79, 248, 0, 0, 56, 0, 4 },
        { 33, 162, 30, 0, 148, 195, 6, 166, 0, 0, 50, 0, 4 },
        { 49, 49, 18, 0, 241, 241, 40, 24, 0, 0, 58, 0, 4 },
        { 49, 49, 141, 0, 241, 241, 232, 120, 0, 0, 58, 0, 4 },
        { 49, 50, 91, 0, 81, 113, 40, 72, 0, 0, 60, 0, 4 },
        { 1, 33, 139, 64, 161, 242, 154, 223, 0, 0, 56, 0, 4 },
        { 33, 33, 139, 8, 162, 161, 22, 223, 0, 0, 56, 0, 4 },
        { 49, 49, 139, 0, 244, 241, 232, 120, 0, 0, 58, 0, 4 },
        { 49, 49, 18, 0, 241, 241, 40, 24, 0, 0, 58, 0, 4 },
        { 49, 33, 21, 0, 221, 86, 19, 38, 1, 0, 56, 0, 4 }, // 40
        { 49, 33, 22, 0, 221, 102, 19, 6, 1, 0, 56, 0, 4 },
        { 113, 49, 73, 0, 209, 97, 28, 12, 1, 0, 56, 0, 4 },
        { 33, 35, 77, 128, 113, 114, 18, 6, 1, 0, 50, 0, 4 },
        { 241, 225, 64, 0, 241, 111, 33, 22, 1, 0, 50, 0, 4 },
        { 2, 1, 26, 128, 245, 133, 117, 53, 1, 0, 48, 0, 4 },
        { 2, 1, 29, 128, 245, 243, 117, 244, 1, 0, 48, 0, 4 },
        { 16, 17, 65, 0, 245, 242, 5, 195, 1, 0, 50, 0, 4 },
        { 33, 162, 155, 1, 177, 114, 37, 8, 1, 0, 62, 0, 4 },
        { 161, 33, 152, 0, 127, 63, 3, 7, 1, 1, 48, 0, 4 },
        { 161, 97, 147, 0, 193, 79, 18, 5, 0, 0, 58, 0, 4 }, // 50
        { 33, 97, 24, 0, 193, 79, 34, 5, 0, 0, 60, 0, 4 },
        { 49, 114, 91, 131, 244, 138, 21, 5, 0, 0, 48, 0, 4 },
        { 161, 97, 144, 0, 116, 113, 57, 103, 0, 0, 48, 0, 4 },
        { 113, 114, 87, 0, 84, 122, 5, 5, 0, 0, 60, 0, 4 },
        { 144, 65, 0, 0, 84, 165, 99, 69, 0, 0, 56, 0, 4 },
        { 33, 33, 146, 1, 133, 143, 23, 9, 0, 0, 60, 0, 4 },
        { 33, 33, 148, 5, 117, 143, 23, 9, 0, 0, 60, 0, 4 },
        { 33, 97, 148, 0, 118, 130, 21, 55, 0, 0, 60, 0, 4 },
        { 49, 33, 67, 0, 158, 98, 23, 44, 1, 1, 50, 0, 4 },
        { 33, 33, 155, 0, 97, 127, 106, 10, 0, 0, 50, 0, 4 }, // 60
        { 97, 34, 138, 6, 117, 116, 31, 15, 0, 0, 56, 0, 4 },
        { 161, 33, 134, 131, 114, 113, 85, 24, 1, 0, 48, 0, 4 },
        { 33, 33, 77, 0, 84, 166, 60, 28, 0, 0, 56, 0, 4 },
        { 49, 97, 143, 0, 147, 114, 2, 11, 1, 0, 56, 0, 4 },
        { 49, 97, 142, 0, 147, 114, 3, 9, 1, 0, 56, 0, 4 },
        { 49, 97, 145, 0, 147, 130, 3, 9, 1, 0, 58, 0, 4 },
        { 49, 97, 142, 0, 147, 114, 15, 15, 1, 0, 58, 0, 4 },
        { 33, 33, 75, 0, 170, 143, 22, 10, 1, 0, 56, 0, 4 },
        { 49, 33, 144, 0, 126, 139, 23, 12, 1, 1, 54, 0, 4 },
        { 49, 50, 129, 0, 117, 97, 25, 25, 1, 0, 48, 0, 4 }, // 70
        { 50, 33, 144, 0, 155, 114, 33, 23, 0, 0, 52, 0, 4 },
        { 225, 225, 31, 0, 133, 101, 95, 26, 0, 0, 48, 0, 4 },
        { 225, 225, 70, 0, 136, 101, 95, 26, 0, 0, 48, 0, 4 },
        { 161, 33, 156, 0, 117, 117, 31, 10, 0, 0, 50, 0, 4 },
        { 49, 33, 139, 0, 132, 101, 88, 26, 0, 0, 48, 0, 4 },
        { 225, 161, 76, 0, 102, 101, 86, 38, 0, 0, 48, 0, 4 },
        { 98, 161, 203, 0, 118, 85, 70, 54, 0, 0, 48, 0, 4 },
        { 98, 161, 153, 0, 87, 86, 7, 7, 0, 0, 59, 0, 4 },
        { 98, 161, 147, 0, 119, 118, 7, 7, 0, 0, 59, 0, 4 },
        { 34, 33, 89, 0, 255, 255, 3, 15, 2, 0, 48, 0, 4 }, // 80
        { 33, 33, 14, 0, 255, 255, 15, 15, 1, 1, 48, 0, 4 },
        { 34, 33, 70, 128, 134, 100, 85, 24, 0, 0, 48, 0, 4 },
        { 33, 161, 69, 0, 102, 150, 18, 10, 0, 0, 48, 0, 4 },
        { 33, 34, 139, 0, 146, 145, 42, 42, 1, 0, 48, 0, 4 },
        { 162, 97, 158, 64, 223, 111, 5, 7, 0, 0, 50, 0, 4 },
        { 32, 96, 26, 0, 239, 143, 1, 6, 0, 2, 48, 0, 4 },
        { 33, 33, 143, 128, 241, 244, 41, 9, 0, 0, 58, 0, 4 },
        { 119, 161, 165, 0, 83, 160, 148, 5, 0, 0, 50, 0, 4 },
        { 97, 177, 31, 128, 168, 37, 17, 3, 0, 0, 58, 0, 4 },
        { 97, 97, 23, 0, 145, 85, 52, 22, 0, 0, 60, 0, 4 }, // 90
        { 113, 114, 93, 0, 84, 106, 1, 3, 0, 0, 48, 0, 4 },
        { 33, 162, 151, 0, 33, 66, 67, 53, 0, 0, 56, 0, 4 },
        { 161, 33, 28, 0, 161, 49, 119, 71, 1, 1, 48, 0, 4 },
        { 33, 97, 137, 3, 17, 66, 51, 37, 0, 0, 58, 0, 4 },
        { 161, 33, 21, 0, 17, 207, 71, 7, 1, 0, 48, 0, 4 },
        { 58, 81, 206, 0, 248, 134, 246, 2, 0, 0, 50, 0, 4 },
        { 33, 33, 21, 0, 33, 65, 35, 19, 1, 0, 48, 0, 4 },
        { 6, 1, 91, 0, 116, 165, 149, 114, 0, 0, 48, 0, 4 },
        { 34, 97, 146, 131, 177, 242, 129, 38, 0, 0, 60, 0, 4 },
        { 65, 66, 77, 0, 241, 242, 81, 245, 1, 0, 48, 0, 4 }, // 100
        { 97, 163, 148, 128, 17, 17, 81, 19, 1, 0, 54, 0, 4 },
        { 97, 161, 140, 128, 17, 29, 49, 3, 0, 0, 54, 0, 4 },
        { 164, 97, 76, 0, 243, 129, 115, 35, 1, 0, 52, 0, 4 },
        { 2, 7, 133, 3, 210, 242, 83, 246, 0, 1, 48, 0, 4 },
        { 17, 19, 12, 128, 163, 162, 17, 229, 1, 0, 48, 0, 4 },
        { 17, 17, 6, 0, 246, 242, 65, 230, 1, 2, 52, 0, 4 },
        { 147, 145, 145, 0, 212, 235, 50, 17, 0, 1, 56, 0, 4 },
        { 4, 1, 79, 0, 250, 194, 86, 5, 0, 0, 60, 0, 4 },
        { 33, 34, 73, 0, 124, 111, 32, 12, 0, 1, 54, 0, 4 },
        { 49, 33, 133, 0, 221, 86, 51, 22, 1, 0, 58, 0, 4 }, // 110
        { 32, 33, 4, 129, 218, 143, 5, 11, 2, 0, 54, 0, 4 },
        { 5, 3, 106, 128, 241, 195, 229, 229, 0, 0, 54, 0, 4 },
        { 7, 2, 21, 0, 236, 248, 38, 22, 0, 0, 58, 0, 4 },
        { 5, 1, 157, 0, 103, 223, 53, 5, 0, 0, 56, 0, 4 },
        { 24, 18, 150, 0, 250, 248, 40, 229, 0, 0, 58, 0, 4 },
        { 16, 0, 134, 3, 168, 250, 7, 3, 0, 0, 54, 0, 4 },
        { 17, 16, 65, 3, 248, 243, 71, 3, 2, 0, 52, 0, 4 },
        { 1, 16, 142, 0, 241, 243, 6, 2, 2, 0, 62, 0, 4 },
        { 14, 192, 0, 0, 31, 31, 0, 255, 0, 3, 62, 0, 4 },
        { 6, 3, 128, 136, 248, 86, 36, 132, 0, 2, 62, 0, 4 }, // 120
        { 14, 208, 0, 5, 248, 52, 0, 4, 0, 3, 62, 0, 4 },
        { 14, 192, 0, 0, 246, 31, 0, 2, 0, 3, 62, 0, 4 },
        { 213, 218, 149, 64, 55, 86, 163, 55, 0, 0, 48, 0, 4 },
        { 53, 20, 92, 8, 178, 244, 97, 21, 2, 0, 58, 0, 4 },
        { 14, 208, 0, 0, 246, 79, 0, 245, 0, 3, 62, 0, 4 },
        { 38, 228, 0, 0, 255, 18, 1, 22, 0, 1, 62, 0, 4 },
        { 0, 0, 0, 0, 243, 246, 240, 201, 0, 2, 62, 0, 4 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, // 128
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 11, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12, 0 }, // 140
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 13, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 14, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 15, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 17, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 19, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 21, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 22, 0 }, // 150
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 23, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 24, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 25, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 26, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 27, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 28, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 29, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 31, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0 }, // 160
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 33, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 34, 0 },
        { 16, 17, 68, 0, 248, 243, 119, 6, 2, 0, 56, 35, 4 }, // 163
        { 16, 17, 68, 0, 248, 243, 119, 6, 2, 0, 56, 36, 4 },
        { 2, 17, 7, 0, 249, 248, 255, 255, 0, 0, 56, 37, 4 },
        { 0, 0, 0, 0, 252, 250, 5, 23, 2, 0, 62, 38, 4 },
        { 0, 1, 2, 0, 255, 255, 7, 8, 0, 0, 48, 39, 4 },
        { 0, 0, 0, 0, 252, 250, 5, 23, 2, 0, 62, 40, 4 },
        { 0, 0, 0, 0, 246, 246, 12, 6, 0, 0, 52, 41, 4 },
        { 12, 18, 0, 0, 246, 251, 8, 71, 0, 2, 58, 42, 4 }, // 170
        { 0, 0, 3, 0, 248, 246, 42, 69, 0, 1, 52, 43, 4 },
        { 12, 18, 0, 5, 246, 123, 8, 71, 0, 2, 58, 44, 4 },
        { 0, 0, 3, 0, 248, 246, 42, 69, 0, 1, 52, 45, 4 },
        { 12, 18, 0, 0, 246, 203, 2, 67, 0, 2, 58, 46, 4 },
        { 0, 0, 3, 0, 248, 246, 42, 69, 0, 1, 52, 47, 4 },
        { 0, 0, 3, 0, 248, 246, 42, 69, 0, 1, 52, 48, 4 },
        { 14, 208, 0, 0, 246, 159, 0, 2, 0, 3, 62, 49, 4 },
        { 0, 0, 3, 0, 248, 246, 42, 69, 0, 1, 52, 50, 4 },
        { 14, 7, 8, 74, 248, 244, 66, 228, 0, 3, 62, 51, 4 },
        { 14, 208, 0, 10, 245, 159, 48, 2, 0, 0, 62, 52, 4 }, // 180
        { 14, 7, 10, 93, 228, 245, 228, 229, 3, 1, 54, 53, 4 },
        { 2, 5, 3, 10, 180, 151, 4, 247, 0, 0, 62, 54, 4 },
        { 78, 158, 0, 0, 246, 159, 0, 2, 0, 3, 62, 55, 4 },
        { 17, 16, 69, 8, 248, 243, 55, 5, 2, 0, 56, 56, 4 },
        { 14, 208, 0, 0, 246, 159, 0, 2, 0, 3, 62, 57, 4 },
        { 128, 16, 0, 13, 255, 255, 3, 20, 3, 0, 60, 58, 4 },
        { 14, 7, 8, 81, 248, 244, 66, 228, 0, 3, 62, 59, 4 },
        { 6, 2, 11, 0, 245, 245, 12, 8, 0, 0, 54, 60, 4 },
        { 1, 2, 0, 0, 250, 200, 191, 151, 0, 0, 55, 61, 4 },
        { 1, 1, 81, 0, 250, 250, 135, 183, 0, 0, 54, 62, 4 }, // 190
        { 1, 2, 84, 0, 250, 248, 141, 184, 0, 0, 54, 63, 4 },
        { 1, 2, 89, 0, 250, 248, 136, 182, 0, 0, 54, 64, 4 },
        { 1, 0, 0, 0, 249, 250, 10, 6, 3, 0, 62, 65, 4 },
        { 0, 0, 128, 0, 249, 246, 137, 108, 3, 0, 62, 66, 4 },
        { 3, 12, 128, 8, 248, 246, 136, 182, 3, 0, 63, 67, 4 },
        { 3, 12, 133, 0, 248, 246, 136, 182, 3, 0, 63, 68, 4 },
        { 14, 0, 64, 8, 118, 119, 79, 24, 0, 2, 62, 69, 4 },
        { 14, 3, 64, 0, 200, 155, 73, 105, 0, 2, 62, 70, 4 },
        { 215, 199, 220, 0, 173, 141, 5, 5, 3, 0, 62, 71, 4 },
        { 215, 199, 220, 0, 168, 136, 4, 4, 3, 0, 62, 72, 4 }, // 200
        { 128, 17, 0, 0, 246, 103, 6, 23, 3, 3, 62, 73, 4 },
        { 128, 17, 0, 9, 245, 70, 5, 22, 2, 3, 62, 74, 4 },
        { 6, 21, 63, 0, 0, 247, 244, 245, 0, 0, 49, 75, 4 },
        { 6, 18, 63, 0, 0, 247, 244, 245, 3, 0, 48, 76, 4 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 77, 0 }, // 205
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 78, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 79, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 80, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 81, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 82, 0 }, // 210
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 83, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 84, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 85, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 86, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 87, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 88, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 89, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 90, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 91, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 92, 0 }, // 220
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 93, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 94, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 95, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 96, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 97, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 98, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 99, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 101, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 102, 0 }, // 230
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 103, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 104, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 105, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 106, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 107, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 108, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 109, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 110, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 111, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 112, 0 }, // 240
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 113, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 114, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 115, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 116, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 117, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 118, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 119, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 120, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 121, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 122, 0 }, // 250
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 123, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 124, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 125, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 126, 0 },
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0 }
    };

    private static final int[][] opl_drum_maps_ = {
        { 255, 0 }, // 0
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 10
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 20
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 30
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 35, 35 },
        { 35, 35 },
        { 37, 52 },
        { 38, 48 },
        { 39, 58 },
        { 40, 60 }, // 40
        { 41, 47 },
        { 42, 43 },
        { 41, 49 },
        { 44, 43 },
        { 41, 51 },
        { 46, 43 },
        { 41, 54 },
        { 41, 57 },
        { 49, 72 },
        { 41, 60 }, // 50
        { 51, 76 },
        { 52, 84 },
        { 53, 36 },
        { 54, 76 },
        { 55, 84 },
        { 56, 83 },
        { 57, 84 },
        { 58, 24 },
        { 51, 77 },
        { 60, 60 }, // 60
        { 61, 65 },
        { 62, 59 },
        { 63, 51 },
        { 64, 45 },
        { 65, 71 },
        { 66, 60 },
        { 67, 58 },
        { 68, 53 },
        { 69, 64 },
        { 70, 71 }, // 70
        { 71, 61 },
        { 72, 61 },
        { 73, 48 },
        { 74, 48 },
        { 75, 69 },
        { 76, 68 },
        { 77, 63 },
        { 78, 74 },
        { 79, 60 },
        { 80, 80 }, // 80
        { 81, 64 },
        { 82, 69 },
        { 83, 73 },
        { 84, 75 },
        { 85, 68 },
        { 86, 48 },
        { 87, 53 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 90
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 100
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 110
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }, // 120
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 },
        { 255, 0 }
    };

    static {
        opl_timbres = Arrays.stream(opl_timbres_).map(opl_timbre::new).toArray(NukedPlayer.opl_timbre[]::new);
        opl_drum_maps = Arrays.stream(opl_drum_maps_).map(opl_drum_map::new).toArray(NukedPlayer.opl_drum_map[]::new);
    }
}