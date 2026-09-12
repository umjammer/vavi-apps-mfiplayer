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
import java.util.Arrays;

import vavi.sound.yamaha.smaf.enums.Enums.VoiceType;
import vavi.sound.yamaha.smaf.enums.Note;
import vavi.sound.yamaha.smaf.voice.VM35FMVoice;
import vavi.sound.yamaha.smaf.voice.VM35VoicePC;
import vavi.sound.yamaha.smaf.voice.VMAFMVoice;
import vavi.sound.yamaha.smaf.voice.VMAVoicePC;
import vavi.util.StringUtil;

import static java.lang.System.getLogger;
import static vavi.sound.midi.MidiUtil.decode87;
import static vavi.sound.smaf.vavi.message.MachineDependentMessage.SYSEX_PACKED;
import static vavi.sound.yamaha.smaf.voice.VM35Voice.VM35FMVoiceVersion.VM5;


/**
 * The voices an MFi or SMAF file sends, as the timbres of an OPL3 synthesizer.
 * <p>
 * This is neither the YMF262 nor a bank: it is the exclusive layer between them, the
 * MA-1 ~ MA-5 voice messages of a file turned into what an OPL3 sounds. A synthesizer -
 * {@link NukedSynthesizer} and {@link MatsuokaSynthesizer} both - hands it every exclusive
 * its receiver does not answer itself (the universal master volume), and what comes out is
 * </p>
 * <ul>
 *  <li>an FM voice, which goes to the {@link Timbres} of that synthesizer as the patch it
 *      is for - whichever bank that patch came from, the file has the last word on it</li>
 *  <li>a wave table (WT) voice, which is no timbre at all and goes to the adpcm engine
 *      instead, see {@link NukedWaveTable}</li>
 * </ul>
 * <p>
 * The messages are the SMAF ones: an MFi file's tone and wave messages arrive as the very
 * same exclusives, packed 8 bit into 7,
 * see {@code vavi.sound.mfi.vavi.sequencer.SmafExclusive} of {@code vavi-sound}.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/03/12 umjammer initial version, in {@link NukedSynthesizer} <br>
 *          0.01 2026-09-11 nsano wave table voices, fix the 8 to 7 bit unpacking <br>
 *          0.02 2026-09-12 nsano out of the synthesizer, which is a YMF262 one <br>
 * @see NukedSynthesizer
 * @see NukedWaveTable
 */
class SmafVoices {

    private static final Logger logger = getLogger(SmafVoices.class.getName());

    /**
     * What a synthesizer does with an FM voice of a file, its timbres being its own
     * business: {@link NukedSynthesizer} has {@link NukedPlayer.opl_timbre} ones and
     * {@link MatsuokaSynthesizer} {@link vavi.sound.midi.ymf262.OplInstrument.Opl3Instrument}
     * ones, and {@link #toOpl3Registers} is the OPL3 image both are built from.
     */
    interface Timbres {

        /**
         * Sounds a voice as the patch it is for, whichever bank that patch came from.
         *
         * @param bank 0 for a melody voice, 128 for a drum one
         * @param program the program, or 128 + the note of a drum voice
         * @param voice at least {@link #OPERATORS} operators
         */
        void setVoice(int bank, int program, VM35FMVoice voice);
    }

    /** where an FM voice goes */
    private final Timbres timbres;

    /** where a wave table voice goes */
    private final NukedWaveTable waveTable;

    /**
     * @param timbres the timbres of the synthesizer, whose patches a voice replaces
     * @param waveTable the wave table voices of the synthesizer
     */
    SmafVoices(Timbres timbres, NukedWaveTable waveTable) {
        this.timbres = timbres;
        this.waveTable = waveTable;
    }

    /**
     * Takes an exclusive, when it is one of a voice.
     * <pre>
     *  45 7f &lt;encode87(43 ... f7)&gt; f7   an MFi tone or wave message
     *  43 ...                           a smaf exclusive as it is
     * </pre>
     *
     * @param data the exclusive, without its leading {@code 0xf0}
     * @return false when it is none of the above, for the caller to log
     */
    boolean process(byte[] data) {
        switch (data[0] & 0xff) {
            case 0x43 -> // yamaha
                processYamahaSysexMessage(data);
            case 0x45 -> { // vavi
                if (data.length < 2 || (data[1] & 0xff) != SYSEX_PACKED) {
                    return false;
                }
                processYamahaSmafSysexMessage(data); // (f0) 45 7f ... 7f
            }
            default -> {
                return false;
            }
        }
        return true;
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
            boolean percussion = x.isForDrum();
            int bank = percussion ? 128 : 0;
            int program = percussion ? (128 + (x.drumNote != null ? x.drumNote.note : 0)) : x.pc;
            timbres.setVoice(bank, program, fmVoice);
        }
    }

    /** how many registers {@link #toOpl3Registers} is */
    static final int REGISTERS = 11;

    /**
     * The OPL3 registers a VM35 (MA-3 / MA-5) FM voice becomes, which is the one thing an
     * OPL3 engine here needs of a voice - what it keeps them in is its own.
     * <pre>
     *  [0] [1]  FLG / MULT  (reg 0x20) of operator 0 and 1
     *  [2] [3]  KSL / TL    (reg 0x40)
     *  [4] [5]  AR / DR     (reg 0x60)
     *  [6] [7]  SL / RR     (reg 0x80)
     *  [8] [9]  WS          (reg 0xe0)
     *  [10]     FB / CNT    (reg 0xc0), without the two stereo bits
     * </pre>
     * <p>
     * Only the first two operators of the voice are used: a four operator MA-3 voice is
     * two OPL3 channels, and the algorithms of the two do not pair up anyway - A5
     * (FB(1)-&gt;2 + FB(3)-&gt;4), which is over half of the MA-3 preset voices, is two
     * independent two operator voices and no OPL3 four operator channel at all.
     * </p>
     */
    static int[] toOpl3Registers(VM35FMVoice voice) {
        int[] registers = new int[REGISTERS];
        var op0 = voice.operators.get(0);
        var op1 = voice.operators.get(1);

        registers[0] = (op0.eam ? 0x80 : 0) | (op0.evb ? 0x40 : 0) | (op0.sus ? 0x20 : 0) | (op0.ksr ? 0x10 : 0) | (op0.multi.ordinal() & 0x0f);
        registers[1] = (op1.eam ? 0x80 : 0) | (op1.evb ? 0x40 : 0) | (op1.sus ? 0x20 : 0) | (op1.ksr ? 0x10 : 0) | (op1.multi.ordinal() & 0x0f);

        registers[2] = (op0.ksl << 6) | (op0.tl & 0x3f);
        registers[3] = (op1.ksl << 6) | (op1.tl & 0x3f);

        registers[4] = (op0.ar << 4) | (op0.dr & 0x0f);
        registers[5] = (op1.ar << 4) | (op1.dr & 0x0f);

        registers[6] = (op0.sl << 4) | (op0.rr & 0x0f);
        registers[7] = (op1.sl << 4) | (op1.rr & 0x0f);

        registers[8] = op0.ws & 0x07;
        registers[9] = op1.ws & 0x07;

        int fb = op0.fb & 0x07;
        // OPL3's CNT bit, 0: the first operator modulates the second, 1: both sound.
        // What this asks of the MA-3 algorithm is whether the first one modulates the
        // second, which A0, A4 and A5 do. The low bit of the algorithm is not that test:
        // A5 is over half of the MA-3 preset voices and would come out as two carriers.
        int connection = switch (voice.alg) {
            case A0, A4, A5 -> 0;
            default -> 1;
        };
        registers[10] = (fb << 1) | connection;

        return registers;
    }

    /** */
    void processYamahaSysexMessage(byte[] data) {
        logger.log(Level.DEBUG, "midi sysex: YAMAHA <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n%s".formatted(StringUtil.getDump(data, 32)));
        logger.log(Level.DEBUG, "midi sysex: YAMAHA unhandled");
    }
}
