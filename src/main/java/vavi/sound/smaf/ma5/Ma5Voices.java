/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.smaf.ma5;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


/**
 * The voices the MA-5 emulator plays a note of midi with, which it has none of.
 * <p>
 * The dll initialized for midi ({@link Ma5Device}) is the sound source without the driver above
 * it, and the driver is what has the voices of the phone: a note on a program nobody has given a
 * voice is silence. mmftool gives it the ones of {@code DefMA3_16.vm3} (its {@code PresetVoices}),
 * a file of it which sits beside the dll, and this does what it does.
 * <p>
 * The file is big endian, {@code "FMM3" u32 size} and records of
 * <pre>
 *  u16 no, u8 size, bm ll pc na type name[16] &lt;voice&gt;
 * </pre>
 * where the voice is the one of an MA-5 voice exclusive, 8 bit: an fm one its key, 2 global bytes
 * and 7 per operator, a wave table one 16 bytes. It goes to the dll as the MA-3 real time exclusive,
 * {@code f0 43 79 06 7f 01 bm ll pc na type <voice packed 7 bit> f7}.
 * <p>
 * The melody voices of the bank 0 are given the banks 0 ~ 9 as well and the drum kit 0 the kits
 * 2 ~ 9, as mmftool does it, so that a song selecting one of those finds a voice there. The dll
 * has those ten banks and ten kits and no more (bank select lsb and program 0 ~ 9, it refuses the
 * rest), and the melody bank 10 and the drum kit 10 of the file are left out: mmftool's
 * {@code PresetVoices} stops at the first of them, and ends up with the kit 1 in the kits 2 ~ 9.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-21 nsano initial version <br>
 * @see "mmftool voice.c"
 */
public final class Ma5Voices {

    /** the file of them, beside the dll */
    public static final String VM3 = "DefMA3_16.vm3";

    private static final int MELODY_BANK = 0x7c;
    private static final int PERCUSSION_BANK = 0x7d;

    /** the melody banks (bank select lsb) and the drum kits (program) the dll has, 0 ~ 9 */
    private static final int BANKS = 10;

    /** bm ll pc na type name[16] */
    private static final int RECORD_HEADER = 5 + 16;

    private Ma5Voices() {
    }

    /**
     * The voices of the file, as the exclusives which give them to the dll.
     *
     * @param vm3 {@link #VM3}
     */
    public static List<byte[]> presets(Path vm3) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(vm3));
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (magic[0] != 'F' || magic[1] != 'M' || magic[2] != 'M' || magic[3] != '3') {
            throw new IOException("not a vm3: " + vm3);
        }
        int end = Math.min(buffer.limit(), 8 + buffer.getInt());

        List<byte[]> exclusives = new ArrayList<>();
        while (buffer.position() + 3 <= end) {
            buffer.getShort(); // the number of the record
            int size = buffer.get() & 0xff;
            if (size < RECORD_HEADER || buffer.position() + size > end) {
                throw new IOException("broken vm3 at " + buffer.position());
            }
            int bm = buffer.get() & 0xff;
            int ll = buffer.get() & 0xff;
            int pc = buffer.get() & 0xff;
            int na = buffer.get() & 0xff;
            int type = buffer.get() & 0xff;
            buffer.position(buffer.position() + 16); // the name
            byte[] voice = new byte[size - RECORD_HEADER];
            buffer.get(voice);

            if (ll >= BANKS || (bm == PERCUSSION_BANK && pc >= BANKS)) {
                // the melody bank 10 and the drum kit 10 of the file, which the dll does not take
                continue;
            }
            if (bm == MELODY_BANK && ll == 0) {
                for (int bank = 0; bank < BANKS; bank++) {
                    exclusives.add(exclusive(bm, bank, pc, na, type, voice));
                }
            } else if (bm == PERCUSSION_BANK && pc == 0) {
                exclusives.add(exclusive(bm, ll, pc, na, type, voice));
                for (int kit = 2; kit < BANKS; kit++) {
                    exclusives.add(exclusive(bm, ll, kit, na, type, voice));
                }
            } else {
                exclusives.add(exclusive(bm, ll, pc, na, type, voice));
            }
        }
        return exclusives;
    }

    /** {@code f0 43 79 06 7f 01 bm ll pc na type <voice packed 7 bit> f7} */
    private static byte[] exclusive(int bm, int ll, int pc, int na, int type, byte[] voice) {
        byte[] message = new byte[11 + voice.length + (voice.length + 6) / 7 + 1];
        message[0] = (byte) 0xf0;
        message[1] = 0x43;
        message[2] = 0x79;
        message[3] = 0x06;
        message[4] = 0x7f;
        message[5] = 0x01;
        message[6] = (byte) (bm & 0x7f);
        message[7] = (byte) (ll & 0x7f);
        message[8] = (byte) (pc & 0x7f);
        message[9] = (byte) (na & 0x7f);
        message[10] = (byte) (type & 0x7f);
        int at = 11;
        // a flag byte holding the bit 7 of the seven bytes after it, the first of them in its bit 6
        for (int i = 0; i < voice.length; i += 7) {
            int n = Math.min(7, voice.length - i), flags = 0;
            for (int j = 0; j < n; j++) {
                flags |= ((voice[i + j] >> 7) & 1) << (6 - j);
            }
            message[at++] = (byte) flags;
            for (int j = 0; j < n; j++) {
                message[at++] = (byte) (voice[i + j] & 0x7f);
            }
        }
        message[at] = (byte) 0xf7;
        return message;
    }
}
