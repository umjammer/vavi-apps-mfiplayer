/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.midi.ymf262;

import javax.sound.midi.Soundbank;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import vavi.sound.midi.ymf262.SbiSoundbankReader.SbiPatch;
import vavi.util.Debug;

import static vavi.sound.midi.ymf262.SbiSoundbankReader.loadSbis;


/**
 * SbiSoundbankReaderTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2025-03-09 nsano initial version <br>
 */
class SbiSoundbankReaderTest {

    @Test
    void test1() throws Exception {
        InputStream is = SbiSoundbankReaderTest.class.getResourceAsStream("/opl3/std.o3");
//        InputStream is = SbiSoundbankReaderTest.class.getResourceAsStream("/opl3/drums.o3");
Debug.println(is);
//        Soundbank soundbank = MidiSystem.getSoundbank(is);
        Soundbank soundbank = new SbiSoundbankReader().getSoundbank(is);
Debug.println(soundbank.getInstruments().length);
        Arrays.stream(soundbank.getInstruments()).forEach(System.err::println);
    }

    /** */
    public static void main(String[] args) throws Exception {
        Path p = Path.of(args[0]);
Debug.println(p);
        int len = args[0].endsWith(".o3") ? SbiSoundbankReader.DATA_LEN_4OP : SbiSoundbankReader.DATA_LEN_2OP;
        try (var f = new BufferedInputStream(Files.newInputStream(p))) {
            SbiPatch[] patches = loadSbis(f);
            Arrays.stream(patches).forEach(SbiSoundbankReader::show_op);
        }
    }
}
