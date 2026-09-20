/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ucs;

import vavi.sound.faith.FaithRom;
import vavi.sound.mfi.ucs.UcsMfiSynthesizer.UcsMfiReceiver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * UcsAudioEngineTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-15 nsano initial version <br>
 */
@EnabledIf("dllExists")
class UcsAudioEngineTest {

    static boolean dllExists() {
        return FaithRom.isAvailable();
    }

    /** @return rms of 16 bit stereo */
    private static double render(UcsAudioEngine engine, double seconds) {
        byte[] block = new byte[128 * 4];
        double sum = 0;
        int samples = 0;
        for (int b = 0; b < seconds * UcsAudioEngine.SAMPLE_RATE / 128; b++) {
            engine.render(block, 128);
            for (int i = 0; i < block.length; i += 2) {
                int v = (short) ((block[i] & 0xff) | (block[i + 1] << 8));
                sum += (double) v * v;
                samples++;
            }
        }
        return Math.sqrt(sum / samples);
    }

    @Test
    void presetNoteSoundsAndDiesAway() throws Exception {
        UcsWaveBank.getInstance().clear();
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);

        assertEquals(0, render(engine, 0.1));

        engine.programChange(0, 0);
        engine.noteOn(0, 60, 100);
        double on = render(engine, 0.5);
        assertTrue(on > 100, "rms: " + on);

        engine.noteOff(0, 60);
        render(engine, 3);
        assertEquals(0, render(engine, 0.1));
    }

    /** the first voice sounding */
    private static FuetrekVoice voiceOf(UcsAudioEngine engine) throws Exception {
        java.lang.reflect.Field field = UcsAudioEngine.class.getDeclaredField("voices");
        field.setAccessible(true);
        for (FuetrekVoice voice : (FuetrekVoice[]) field.get(engine)) {
            if (voice != null) return voice;
        }
        return null;
    }

    @Test
    void bankSelectsGroup() throws Exception {
        UcsWaveBank.getInstance().clear();
        FuetrekRom rom = FuetrekRom.getInstance();

        // no bank told: the midi program of the melody group
        UcsAudioEngine engine = new UcsAudioEngine(rom, false);
        engine.programChange(0, 0x41);
        engine.noteOn(0, 60, 100);
        assertEquals(0x79, voiceOf(engine).group);
        assertEquals(0x41, voiceOf(engine).program);

        // bank 3 program 1 is the melody group's 0x41 too
        engine = new UcsAudioEngine(rom, false);
        engine.bankChange(0, 3);
        engine.programChange(0, 0x41);
        engine.noteOn(0, 60, 100);
        assertEquals(0x79, voiceOf(engine).group);
        assertEquals(0x41, voiceOf(engine).program);

        // bank 0: group 0x7d, the mfi 1 tones
        engine = new UcsAudioEngine(rom, false);
        engine.bankChange(0, 0);
        engine.programChange(0, 1);
        engine.noteOn(0, 60, 100);
        assertEquals(0x7d, voiceOf(engine).group);
        assertEquals(1, voiceOf(engine).program);

        // drum bank 0x34: group 0x14 by note
        engine = new UcsAudioEngine(rom, false);
        engine.bankChange(9, 0x34);
        int note = -1;
        for (int k = 0; k < 128 && note < 0; k++) if (rom.instrument(0x14, k) != null && rom.instrument(0x14, k).zone(k) != null) note = k;
        engine.noteOn(9, note, 100);
        assertEquals(0x14, voiceOf(engine).group);
    }

    /** the listener's volume is not overwritten by the song's master volume, they are multiplied */
    @Test
    void hostVolumeSurvivesSongVolume() throws Exception {
        UcsWaveBank.getInstance().clear();
        FuetrekRom rom = FuetrekRom.getInstance();
        javax.sound.midi.Receiver receiver;

        UcsAudioEngine full = new UcsAudioEngine(rom, false);
        receiver = new UcsMfiReceiver(full);
        for (javax.sound.midi.MidiEvent e : new vavi.sound.mfi.vavi.track.MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new vavi.sound.mfi.vavi.MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        full.noteOn(0, 60, 100);
        double loud = render(full, 0.3);

        UcsAudioEngine quiet = new UcsAudioEngine(rom, false);
        receiver = new UcsMfiReceiver(quiet);
        vavi.sound.midi.MidiUtil.volume(receiver, 0.2f);
        for (javax.sound.midi.MidiEvent e : new vavi.sound.mfi.vavi.track.MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new vavi.sound.mfi.vavi.MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        quiet.noteOn(0, 60, 100);
        double soft = render(quiet, 0.3);

        assertEquals(0.2, soft / loud, 0.05, "loud: " + loud + ", soft: " + soft);
    }

    private static UcsAudioEngine.Channel channel(UcsAudioEngine engine, int channel) throws Exception {
        java.lang.reflect.Field field = UcsAudioEngine.class.getDeclaredField("channels");
        field.setAccessible(true);
        return ((UcsAudioEngine.Channel[]) field.get(engine))[channel];
    }

    /** bank select msb is the group, latched by a program change, an even one is drums */
    @Test
    void bankSelectMsbIsGroup() throws Exception {
        UcsWaveBank.getInstance().clear();
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);
        engine.controlChange(0, 0, 0x7d);
        assertEquals(0x79, channel(engine, 0).group);
        engine.programChange(0, 2);
        assertEquals(0x7d, channel(engine, 0).group);
        engine.noteOn(0, 60, 100);
        assertEquals(0x7d, voiceOf(engine).group);

        engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);
        engine.controlChange(3, 0, 0x14);
        engine.programChange(3, 0);
        assertTrue(channel(engine, 3).isDrum());
        engine.noteOn(3, 40, 100);
        assertEquals(0x14, voiceOf(engine).group);
    }

    /** rpn 0, 1, 2 and the universal tunings make the pitch as the dll does */
    @Test
    void pitchControls() throws Exception {
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);
        UcsAudioEngine.Channel c = channel(engine, 0);

        engine.pitchBend(0, 0x3fff);
        assertEquals(((0x1fff * (2 << 7)) >> 4), c.pitchQ16());

        // rpn 0: sensitivity 12
        engine.controlChange(0, 101, 0);
        engine.controlChange(0, 100, 0);
        engine.controlChange(0, 6, 12);
        engine.pitchBend(0, 0x2000 + 0x1000);
        assertEquals(6 << 16, c.pitchQ16());

        // rpn 2: coarse +3, rpn 1: fine +1/2 semitone
        engine.pitchBend(0, 0x2000);
        engine.controlChange(0, 100, 2);
        engine.controlChange(0, 6, 0x43);
        assertEquals(3 << 16, c.pitchQ16());
        engine.controlChange(0, 100, 1);
        engine.controlChange(0, 6, 0x60);
        assertEquals((3 << 16) + 0x8000, c.pitchQ16());

        // data decrement on fine
        engine.controlChange(0, 97, 0);
        assertEquals((3 << 16) + 0x8000 - 0x400, c.pitchQ16());

        // nrpn selected: data entry does nothing
        engine.controlChange(0, 99, 1);
        engine.controlChange(0, 98, 8);
        engine.controlChange(0, 6, 0x7f);
        assertEquals((3 << 16) + 0x8000 - 0x400, c.pitchQ16());

        // universal master coarse -1
        engine.masterCoarseTuning(0x3f);
        assertEquals((2 << 16) + 0x8000 - 0x400, c.pitchQ16());

        // reset all controllers keeps the tunings, gm system on does not
        engine.controlChange(0, 121, 0);
        assertEquals(0, c.bend);
        engine.reset();
        engine.masterCoarseTuning(0x40);
        assertEquals(0, c.pitchQ16());
    }

    /** a note off under the hold pedal waits for the pedal */
    @Test
    void hold() throws Exception {
        UcsWaveBank.getInstance().clear();
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);
        engine.programChange(0, 0);
        engine.controlChange(0, 64, 0x7f);
        engine.noteOn(0, 60, 100);
        render(engine, 0.1);
        engine.noteOff(0, 60);
        FuetrekVoice voice = voiceOf(engine);
        assertTrue(voice.held);
        assertFalse(voice.isReleased());

        engine.controlChange(0, 64, 0);
        assertTrue(voice.isReleased());
    }

    /** a key struck again while it is on goes on until the last note off, as the native player does */
    @Test
    void keyStruckAgainGoesOn() throws Exception {
        UcsWaveBank.getInstance().clear();
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);
        engine.programChange(0, 0);
        engine.noteOn(0, 60, 100);
        FuetrekVoice first = voiceOf(engine);
        render(engine, 0.1);
        engine.noteOn(0, 60, 100);
        engine.noteOff(0, 60);
        assertEquals(first, voiceOf(engine));
        assertFalse(first.isReleased());
        engine.noteOff(0, 60);
        assertTrue(first.isReleased());
    }

    /** mfi pitch bend halves make the word of the native player, 0xe7 is a modulation lane */
    @Test
    void mfiPitchBend() throws Exception {
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);
        UcsAudioEngine.Channel c = channel(engine, 0);

        // e9 0x28 then e4 0x21 (vavi: msb 0x42)
        engine.pitchBendFine(0, 0x28);
        engine.pitchBend(0, 0x42 << 7);
        assertEquals(((((0x21 << 5) + 0x28) << 3) - 0x100) - 0x2000, c.bend);

        // e7 12: a modulation lane, the rpn vavi sends after it does not change the sensitivity
        engine.mfiPitchBendRange(0, 12);
        engine.controlChange(0, 100, 0);
        engine.controlChange(0, 101, 0);
        engine.controlChange(0, 6, 12);
        assertEquals(2 << 7, c.sensitivity);
        assertEquals(24, c.modulation());

        // a plain rpn afterwards is a bend sensitivity
        engine.controlChange(0, 6, 12);
        assertEquals(12 << 7, c.sensitivity);
    }

    @Test
    void drumSounds() throws Exception {
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);

        engine.noteOn(9, 36, 100);
        double on = render(engine, 0.2);
        assertTrue(on > 100, "rms: " + on);
    }
}
