/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import vavi.sound.yamaha.smaf.enums.Enums.VoiceType;

import static java.lang.System.getLogger;


/**
 * The MA-3 preset voice library a ".vm3" ("FMM3") file is.
 * <p>
 * A wave table (WT) voice is no {@link vavi.sound.midi.ymf262.NukedPlayer.opl_timbre},
 * see {@link NukedWaveTable}, so a WT voice whose wave is the chip's own (RM = 1) is a
 * voice this can neither play nor even hand to the wave table - there is no data for a
 * rom wave anywhere. The MA-3 preset library is what gives those a timbre after all: its
 * drum bank holds the same 79 notes once per kit, and the kit next to the standard one is
 * an FM kit, which has an ordinary four operator FM voice under the very name the
 * standard kit has a rom wave under
 * ({@code Snare L}, {@code Bass Drum M}, {@code Hi-Hat Open}, the toms and the cymbals -
 * 21 of them). Reading the library in file order and keeping the first FM voice of each
 * patch therefore gives the standard kit where it is FM and the FM kit where it is not,
 * which is what {@link Vm3SoundbankReader} does.
 * </p>
 * <p>
 * The file is big endian and is a header plus one record per voice:
 * </p>
 * <pre>
 *  "FMM3" &lt;size of all the records&gt;             8 byte header
 *  &lt;record no&gt; &lt;record size&gt;                   {@link #ENTRY_HEADER}
 *  mm ll pc dn vt &lt;name, 16 byte zero padded&gt;   {@link #RECORD_HEADER}
 *  &lt;voice image&gt;                               {@code record size - RECORD_HEADER}
 *     mm: bank MSB, 124 (0x7c) melody, 125 (0x7d) drum
 *     ll: bank LSB, the variation of a melody bank
 *     pc: program, the kit number of a drum bank
 *     dn: drum note, 0 for a melody voice
 *     vt: {@link VoiceType}
 * </pre>
 * <p>
 * The voice image is the plain (8 bit) VM35 one, the same shape an MFi tone carries and
 * {@code NukedSynthesizer#processYamahaSmafSysexMessage} registers, so
 * {@link vavi.sound.yamaha.smaf.voice.VM35FMVoice} and {@link NukedWaveTable.Voice} read
 * it as it is: {@link #FM_VOICE} bytes for an FM voice, {@link #PCM_VOICE} for a WT one.
 * </p>
 * <p>
 * {@code vavi.sound.yamaha.smaf.voice.VM3VoiceLib} of {@code vavi-sound-ma} 0.0.3 cannot
 * be used for this: it reads at most 128 records (a preset library holds 493), and its
 * {@code VM35VoicePC#read} never reads the {@code VM3Lib} record header out of the
 * stream - the go original does it with a {@code binary.Read} of the struct, the port
 * only subtracts its size - so every field comes out 0 and the stream stays where it was.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-12 nsano initial version <br>
 * @see "/usr/local/src/mmftool/memo/vm3.txt"
 * @see "https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool"
 */
class Vm3VoiceLib {

    private static final Logger logger = getLogger(Vm3VoiceLib.class.getName());

    /** "FMM3" */
    private static final int SIGNATURE = 'F' << 24 | 'M' << 16 | 'M' << 8 | '3';

    /** how many bytes of a stream {@link #isVoiceLib} looks at */
    static final int SIGNATURE_LENGTH = 4;

    /** whether a stream starting with these bytes is a voice library, for {@link Vm3SoundbankReader} */
    static boolean isVoiceLib(byte[] header) {
        return header.length >= SIGNATURE_LENGTH &&
                ((header[0] & 0xff) << 24 | (header[1] & 0xff) << 16 |
                 (header[2] & 0xff) << 8 | (header[3] & 0xff)) == SIGNATURE;
    }

    /** record no (2) and record size (1), which the record size does not count */
    private static final int ENTRY_HEADER = 3;

    /** the 16 byte name of a record */
    private static final int NAME_LENGTH = 16;

    /** mm, ll, pc, dn, vt and the name */
    private static final int RECORD_HEADER = 5 + NAME_LENGTH;

    /** drum key, panpot / BO, LFO / PE / ALG and 7 bytes per operator */
    static final int FM_VOICE = 3 + 7 * 4;

    /** the WT (PCM) voice image, Fs ... RM / WaveID */
    static final int PCM_VOICE = 16;

    /** bank MSB of the melody voices */
    static final int MELODY_BANK = 124;

    /** bank MSB of the drum kits */
    static final int DRUM_BANK = 125;

    /**
     * One voice of the library.
     *
     * @param bankMSB {@link #MELODY_BANK} or {@link #DRUM_BANK}
     * @param bankLSB the variation of a melody bank
     * @param pc the program, the kit number of a drum voice
     * @param drumNote the note a drum voice is for, 0 for a melody voice
     * @param voiceType null when the {@code vt} byte names none
     * @param name the name of the voice, for the log
     * @param image the plain VM35 voice image
     */
    record Entry(int bankMSB, int bankLSB, int pc, int drumNote, VoiceType voiceType, String name, byte[] image) {

        /** whether this is a drum voice, the same test {@code VM35VoicePC#isForDrum} makes */
        boolean isForDrum() {
            return drumNote != 0;
        }

        @Override
        public String toString() {
            return "bank %d-%d @%d%s: [%s] %s, %d bytes"
                    .formatted(bankMSB, bankLSB, pc, isForDrum() ? " note " + drumNote : "",
                            name, voiceType, image.length);
        }
    }

    /** the voices of the library, in file order */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * @param is a ".vm3" file
     * @throws IllegalArgumentException when the stream is not one
     */
    Vm3VoiceLib(InputStream is) throws IOException {
        DataInputStream dis = new DataInputStream(is);

        if (dis.readInt() != SIGNATURE) {
            throw new IllegalArgumentException("not a vm3 voice library, the signature must be \"FMM3\"");
        }
        int rest = dis.readInt();

        while (rest >= ENTRY_HEADER + RECORD_HEADER) {
            int number = dis.readUnsignedShort();
            int recordSize = dis.readUnsignedByte();
            rest -= ENTRY_HEADER;

            if (recordSize < RECORD_HEADER || recordSize > rest) {
logger.log(Level.WARNING, "vm3 record " + number + " has a bad size: " + recordSize + ", " + rest + " bytes left");
                break;
            }

            byte[] record = new byte[recordSize];
            dis.readFully(record);
            rest -= recordSize;

            Entry entry = new Entry(
                    record[0] & 0xff,
                    record[1] & 0xff,
                    record[2] & 0xff,
                    record[3] & 0xff,
                    voiceType(record[4] & 0xff, number),
                    name(record),
                    Arrays.copyOfRange(record, RECORD_HEADER, recordSize));
            entries.add(entry);
logger.log(Level.TRACE, "vm3 record " + number + ": " + entry);
        }

        if (rest != 0) {
logger.log(Level.WARNING, "vm3 voice library has " + rest + " bytes left over");
        }
logger.log(Level.DEBUG, "vm3 voice library: " + entries.size() + " voices, " +
        entries.stream().filter(e -> e.voiceType() == VoiceType.PCM).count() + " of them wave table");
    }

    /** @return null when the {@code vt} byte names no {@link VoiceType} */
    private static VoiceType voiceType(int vt, int number) {
        if (vt >= VoiceType.values().length) {
logger.log(Level.DEBUG, "vm3 record %d has an unknown voice type, ignored: %02x".formatted(number, vt));
            return null;
        }
        return VoiceType.values()[vt];
    }

    /** the zero padded name of a record */
    private static String name(byte[] record) {
        int length = 0;
        while (length < NAME_LENGTH && record[5 + length] != 0) {
            length++;
        }
        return new String(record, 5, length, StandardCharsets.US_ASCII);
    }

    /** the voices of the library, in file order */
    List<Entry> getEntries() {
        return entries;
    }
}
