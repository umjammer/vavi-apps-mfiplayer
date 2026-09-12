/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import org.junit.jupiter.api.Test;

import vavi.sound.midi.ymf262.NukedPlayer.opl_timbre;
import vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static vavi.sound.yamaha.smaf.voice.VM35Voice.VM35FMVoiceVersion.VM3Lib;


/**
 * Tests the OPL3 registers a VM35 voice becomes, which both synthesizers of this package
 * build their own timbre out of.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-12 nsano initial version <br>
 * @see YamahaVoices
 */
class YamahaVoicesTest {

    /**
     * A VM35 FM voice: {@code alg}, and of its first operator the TL, the multiplier, the
     * wave shape and the feedback - enough to see every register below arrive.
     */
    private static VM35FMVoice voice(int alg, int tl, int multi, int ws, int fb) throws Exception {
        byte[] image = new byte[Vm3VoiceLib.FM_VOICE];
        image[2] = (byte) alg;                  // LFO, PE, ALG
        image[3 + 3] = (byte) (tl << 2 | 1);    // operator 0: TL, KSL
        image[3 + 5] = (byte) (multi << 4);     // operator 0: MULTI, DT
        image[3 + 6] = (byte) (ws << 3 | fb);   // operator 0: WS, FB
        return new VM35FMVoice(image, VM3Lib);
    }

    @Test
    void registers() throws Exception {
        // A0 is FB(1)->2, the first operator modulates the second
        int[] registers = YamahaVoices.toOpl3Registers(voice(0, 42, 3, 5, 7));

        assertEquals(YamahaVoices.REGISTERS, registers.length);
        assertEquals(3, registers[0], "FLG / MULT");
        assertEquals(1 << 6 | 42, registers[2], "KSL / TL");
        assertEquals(5, registers[8], "WS");
        assertEquals(7 << 1, registers[10], "FB / CNT, the stereo bits are not a voice's");

        // A1 is FB(1) + 2, both sound
        assertEquals(1, YamahaVoices.toOpl3Registers(voice(1, 0, 0, 0, 0))[10] & 1);
        // A5 is FB(1)->2 + FB(3)->4, the first pair modulates
        assertEquals(0, YamahaVoices.toOpl3Registers(voice(5, 0, 0, 0, 0))[10] & 1);
    }

    /** the {@link NukedSynthesizer} timbre of a voice */
    @Test
    void nukedTimbre() throws Exception {
        VM35FMVoice voice = voice(0, 42, 3, 5, 7);
        int[] registers = YamahaVoices.toOpl3Registers(voice);
        opl_timbre timbre = NukedSoundbank.toTimbre(voice);

        assertEquals(registers[0], timbre.mult[0]);
        assertEquals(registers[2], timbre.tl[0]);
        assertEquals(registers[4], timbre.ad[0]);
        assertEquals(registers[6], timbre.sr[0]);
        assertEquals(registers[8], timbre.wf[0]);
        assertEquals(0x30 | registers[10], timbre.fb, "a Nuked timbre carries the stereo bits itself");
    }

    /** the {@link MatsuokaSynthesizer} instrument of the same voice */
    @Test
    void matsuokaInstrument() throws Exception {
        VM35FMVoice voice = voice(0, 42, 3, 5, 7);
        int[] registers = YamahaVoices.toOpl3Registers(voice);
        Opl3Instrument instrument = YmF262Soundbank.toInstrument(voice);

        assertEquals(registers[0], instrument.op[0].flg_mul);
        assertEquals(registers[2], instrument.op[0].ksl_tl);
        assertEquals(registers[4], instrument.op[0].ar_dr);
        assertEquals(registers[6], instrument.op[0].sl_rr);
        assertEquals(registers[8], instrument.op[0].ws);
        assertEquals(registers[1], instrument.op[1].flg_mul);
        assertEquals(registers[10], instrument.fb_algA, "the player adds the stereo bits itself");
        assertEquals(0, instrument.type, "a two operator channel");

        // the player writes all four operators, the two a voice does not use are silent
        for (int op = 2; op < 4; op++) {
            assertNotNull(instrument.op[op]);
            assertEquals(0x3f, instrument.op[op].ksl_tl, "operator " + op);
        }
    }

    /**
     * A bank of a file is {@code Opl3Instrument} ones, which {@link NukedSynthesizer} takes
     * as well - the way there must land on the same timbre as a voice which came straight
     * from a file.
     */
    @Test
    void sameTimbreEitherWay() throws Exception {
        VM35FMVoice voice = voice(0, 42, 3, 5, 7);

        opl_timbre straight = NukedSoundbank.toTimbre(voice);
        opl_timbre viaBank = NukedSoundbank.toTimbre(YmF262Soundbank.toInstrument(voice));

        assertEquals(straight.mult[0], viaBank.mult[0]);
        assertEquals(straight.tl[0], viaBank.tl[0]);
        assertEquals(straight.ad[0], viaBank.ad[0]);
        assertEquals(straight.sr[0], viaBank.sr[0]);
        assertEquals(straight.wf[0], viaBank.wf[0]);
        assertEquals(straight.mult[1], viaBank.mult[1]);
        assertEquals(straight.fb, viaBank.fb);
    }

    /** a voice replaces the timbre of a patch of the player */
    @Test
    void player() throws Exception {
        MatsuokaPlayer player = new MatsuokaPlayer();
        Opl3Instrument instrument = YmF262Soundbank.toInstrument(voice(0, 42, 3, 5, 7));

        player.setInstrument(5, instrument);
        assertSame(instrument, player.opl3_ins[5]);

        player.setDrum(38, instrument);
        assertSame(instrument, player.opl3_drum[38]);

        // a patch the player does not have is a warning, not a throw
        player.setInstrument(-1, instrument);
        player.setDrum(1000, instrument);
    }
}
