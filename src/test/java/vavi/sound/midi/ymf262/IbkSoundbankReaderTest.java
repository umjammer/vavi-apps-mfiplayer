/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static vavi.sound.midi.ymf262.IbkSoundbankReader.dumpIbks;
import static vavi.sound.midi.ymf262.IbkSoundbankReader.loadIbks;


/**
 * IbkSoundbankReaderTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-03-09 nsano initial version <br>
 */
public class IbkSoundbankReaderTest {

    public static void main(String[] args) throws Exception {
        InputStream is = Files.newInputStream(Path.of(args[0]));
        OplInstrument.ibk[] ibks = loadIbks(is);
        dumpIbks(ibks, args[1]);
    }
}
