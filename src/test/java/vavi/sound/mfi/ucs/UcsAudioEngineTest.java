/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ucs;

import vavi.sound.mfi.faith.FaithType4Player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return FaithType4Player.isAvailable();
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
        UcsSequencer.waveBank().clear();
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
        UcsSequencer.waveBank().clear();
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
        UcsSequencer.waveBank().clear();
        FuetrekRom rom = FuetrekRom.getInstance();
        javax.sound.midi.Receiver receiver;

        UcsAudioEngine full = new UcsAudioEngine(rom, false);
        receiver = new UcsSynthesizer.UcsReceiver(full);
        for (javax.sound.midi.MidiEvent e : new vavi.sound.mfi.vavi.track.MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new vavi.sound.mfi.vavi.MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        full.noteOn(0, 60, 100);
        double loud = render(full, 0.3);

        UcsAudioEngine quiet = new UcsAudioEngine(rom, false);
        receiver = new UcsSynthesizer.UcsReceiver(quiet);
        vavi.sound.midi.MidiUtil.volume(receiver, 0.2f);
        for (javax.sound.midi.MidiEvent e : new vavi.sound.mfi.vavi.track.MasterVolumeMessage().init(0, 0xff, 0xb0, 127).getMidiEvents(new vavi.sound.mfi.vavi.MidiContext())) {
            receiver.send(e.getMessage(), -1);
        }
        quiet.noteOn(0, 60, 100);
        double soft = render(quiet, 0.3);

        assertEquals(0.2, soft / loud, 0.05, "loud: " + loud + ", soft: " + soft);
    }

    @Test
    void drumSounds() throws Exception {
        UcsAudioEngine engine = new UcsAudioEngine(FuetrekRom.getInstance(), false);

        engine.noteOn(9, 36, 100);
        double on = render(engine, 0.2);
        assertTrue(on > 100, "rms: " + on);
    }
}
