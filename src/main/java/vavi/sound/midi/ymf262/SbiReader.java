/*
 * The "Artistic License"
 *
 * Copyright Holder: Claudio Matsuoka <claudio@conectiva.com>
 */

package vavi.sound.midi.ymf262;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import vavi.sound.midi.ymf262.OplInstrument.Sbi;
import vavi.util.Debug;
import vavi.util.serdes.Serdes.Util;

import static vavi.sound.midi.ymf262.OplInstrument.SBI_2OP_SIZE;
import static vavi.sound.midi.ymf262.OplInstrument.SBI_4OP_SIZE;


/**
 * SbiReader.
 *
 * TODO WIP
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-01-20 nsano initial version <br>
 */
public class SbiReader {

    static int ins_size = SBI_4OP_SIZE;

    static int read_sbi(Sbi[] sbis, int size) {

        System.err.println("#include \"opl3.h\"\n");
        System.err.println("struct opl3_instrument opl3_ins[] = {");

        int n = 0;
        for (Sbi sbi : sbis) {
            System.err.print("      { ");

            if (Arrays.equals(sbi.magic, new byte[] {'4', 'O', 'P', 0x1a})) {
                ins_size = SBI_4OP_SIZE;
                System.err.print("OPL3_TYPE_4OP,");
            } else if (Arrays.equals(sbi.magic, new byte[] {'2', 'O', 'P', 0x1a})) {
                ins_size = SBI_2OP_SIZE;
                System.err.print("OPL3_TYPE_2OP,");
            } else {
                throw new IllegalArgumentException("not sbi: " + new String(sbi.magic, StandardCharsets.US_ASCII));
            }

            assert (ins_size == SBI_4OP_SIZE);

            System.err.printf("\t/* %3d: %-20.20s */\n", n, new String(sbi.name));

            System.err.print("\t{ { ");
            System.err.printf("0x%02x, ", sbi.A.m_flg_mul);
            System.err.printf("0x%02x, ", sbi.A.m_ksl_tl);
            System.err.printf("0x%02x, ", sbi.A.m_ar_dr);
            System.err.printf("0x%02x, ", sbi.A.m_sl_rr);
            System.err.printf("0x%02x", sbi.A.m_ws);
            System.err.println(" },\t/* OP1 */");

            System.err.print("\t  { ");
            System.err.printf("0x%02x, ", sbi.A.c_flg_mul);
            System.err.printf("0x%02x, ", sbi.A.c_ksl_tl);
            System.err.printf("0x%02x, ", sbi.A.c_ar_dr);
            System.err.printf("0x%02x, ", sbi.A.c_sl_rr);
            System.err.printf("0x%02x", sbi.A.c_ws);
            System.err.println(" },\t/* OP2 */");

            System.err.print("\t  { ");
            System.err.printf("0x%02x, ", sbi.B.m_flg_mul);
            System.err.printf("0x%02x, ", sbi.B.m_ksl_tl);
            System.err.printf("0x%02x, ", sbi.B.m_ar_dr);
            System.err.printf("0x%02x, ", sbi.B.m_sl_rr);
            System.err.printf("0x%02x", sbi.B.m_ws);
            System.err.println(" },\t/* OP3 */");

            System.err.print("\t  { ");
            System.err.printf("0x%02x, ", sbi.B.c_flg_mul);
            System.err.printf("0x%02x, ", sbi.B.c_ksl_tl);
            System.err.printf("0x%02x, ", sbi.B.c_ar_dr);
            System.err.printf("0x%02x, ", sbi.B.c_sl_rr);
            System.err.printf("0x%02x", sbi.B.c_ws);
            System.err.println(" } },\t/* OP4 */");

            System.err.printf("\t0x%02x, 0x%02x, 0x%02x, 0x%02x\n",
                    sbi.A.fb_alg, sbi.B.fb_alg, sbi.fix_dur, sbi.fix_key);
            System.err.print("      }");

            n ++;

            if (n < sbis.length - 1) System.err.print(",");

            System.err.println();
        }

        System.err.println("};");

        return n;
    }

    static Sbi[] load_file(String name, int[] s) throws IOException {
        Path p = Path.of(name);

        List<Sbi> l = new ArrayList<>();
        try (var f = Files.newInputStream(p)) {
            while (f.available() > 0) {
                try {
                    Sbi sbi = new Sbi();
                    Util.deserialize(f, sbi);
                    l.add(sbi);
                } catch (EOFException e) {
Debug.println("size: " + l.size() + ", rest: " + f.available());
                    break;
                }
            }
        }

        s[0] = (int) Files.size(p);

        return l.toArray(Sbi[]::new);
    }

    static int load_instruments(String f) throws IOException {
        Sbi[] buf;
        int n;
        int[] size = new int[1];

        buf = load_file(f, size);
        n = read_sbi(buf, size[0]);

        return n;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SbiReader <o3file>");
            System.exit(1);
        }
        load_instruments(args[0]);
    }
}