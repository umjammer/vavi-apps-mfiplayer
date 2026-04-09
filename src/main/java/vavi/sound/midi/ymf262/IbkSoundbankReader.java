/*
 * The "Artistic License"
 *
 * Copyright Holder: Claudio Matsuoka <claudio@conectiva.com>
 */

package vavi.sound.midi.ymf262;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Soundbank;
import javax.sound.midi.spi.SoundbankReader;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import vavi.sound.midi.ymf262.OplInstrument.Ibk;
import vavi.util.Debug;
import vavi.util.serdes.Serdes;


/**
 * IbkSoundbankReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/01/20 umjammer initial version <br>
 * @see "https://moddingwiki.shikadi.net/wiki/IBK_Format"
 */
public class IbkSoundbankReader extends SoundbankReader {

    @Override
    public Soundbank getSoundbank(URL url) throws InvalidMidiDataException, IOException {
        return getSoundbank(url.openStream());
    }

    @Override
    public Soundbank getSoundbank(InputStream stream) throws InvalidMidiDataException, IOException {
        return getSoundbankInternal(stream);
    }

    @Override
    public Soundbank getSoundbank(File file) throws InvalidMidiDataException, IOException {
        return getSoundbank(new FileInputStream(file));
    }

    static void dumpIbks(Ibk[] ibks, String name) {

        System.err.println("#include \"opl3.h\"\n");
        System.err.printf("struct opl2_instrument %s[128] = {\n", name);

        int n = 0;
        for (Ibk ibk : ibks) {
            System.err.print("      { ");

            System.err.printf("\t/* %3d %s */\n", n, ibk.name);

            System.err.print("\t{ { ");
            System.err.printf("0x%02x, ", ibk.i.m_flg_mul);
            System.err.printf("0x%02x, ", ibk.i.m_ksl_tl);
            System.err.printf("0x%02x, ", ibk.i.m_ar_dr);
            System.err.printf("0x%02x, ", ibk.i.m_sl_rr);
            System.err.printf("0x%02x", ibk.i.m_ws);
            System.err.print(" },\t/* OP1 */\n");

            System.err.print("\t  { ");
            System.err.printf("0x%02x, ", ibk.i.c_flg_mul);
            System.err.printf("0x%02x, ", ibk.i.c_ksl_tl);
            System.err.printf("0x%02x, ", ibk.i.c_ar_dr);
            System.err.printf("0x%02x, ", ibk.i.c_sl_rr);
            System.err.printf("0x%02x", ibk.i.c_ws);
            System.err.println(" } },\t/* OP2 */");

            System.err.printf("\t0x%02x, 0x%02x\n", ibk.i.fb_alg, ibk.dpitch);
            System.err.print("      }");

            if (n < 127)
                System.err.print(",");

            System.err.println();

            n++;
        }

        System.err.println("};");
    }

    static Ibk[] loadIbks(InputStream is) throws IOException {
        List<Ibk> l = new ArrayList<>();
        byte[] b = is.readNBytes(4);
        if (!Arrays.equals(b, new byte[] {'I', 'B', 'K', 0x1a}))
            throw new IllegalArgumentException("not ibk file");

        int c = 0;
        while (c < 128 && is.available() > 0) {
            try {
                Ibk ibk = new Ibk();
                Serdes.Util.deserialize(is, ibk);
                l.add(ibk);
//Debug.println("size: " + l.size() + ", " + f.available() + ", " + ibk);
                c++;
            } catch (EOFException e) {
Debug.println("size: " + l.size() + ", rest: " + is.available());
                throw e;
            }
        }
        c = 0;
        while (c < 128 && is.available() > 0) {
            try {
                byte[] n = is.readNBytes(9);
                l.get(c).name = new String(n, StandardCharsets.US_ASCII).replace("\u0000", "");
                c++;
            } catch (EOFException e) {
                throw e;
            }
        }

        return l.toArray(Ibk[]::new);
    }

    Soundbank getSoundbankInternal(InputStream is) throws IOException {
        return null;
    }
}
