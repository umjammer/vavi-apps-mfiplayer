/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import vavi.sound.mfi.MfiChip.Condition;
import vavi.sound.mfi.MfiChip.Detection;
import vavi.util.Debug;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * MfiChipTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
@EnabledIf("localPropertiesExists")
public class MfiChipTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "../vavi-sound/tmp/samples/n703id/02 TRANSPARENT.mld,YAMAHA",
            "tmp/ucs/Judgment_ft.mld,FUETREK",
            "/Users/nsano/Public/np2/mfi/Ringtones (MLD)/Ringtones from Cuebus F901iC/114_8981100010347092588F.MLD,ROHM"
    })
    void test(String mld, String chip) throws Exception {
        Path path = Paths.get(mld);
        Sequence seq = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(path)));

        Condition condition = Condition.create(seq);
Debug.print(condition);
        Detection detection = MfiChip.detect(condition);
Debug.print("reason: " + detection.reason());
        assertEquals(MfiChip.valueOf(chip), detection.chip());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0_2003SEP078_0098_01_NULL_01_n16.mld,YAMAHA",
            "0_2003SEP078_0098_01_NULL_01_n40.mld,YAMAHA",
            "0_2003SEP078_0098_01_NULL_01_so16.mld,YAMAHA",
            "0_2003SEP078_0098_01_NULL_01_so40.mld,YAMAHA",
            "0_2003SEP078_0098_01_NULL_01_f16.mld,ROHM",
            "0_2003SEP078_0098_01_NULL_01_f40.mld,ROHM",
            "0_2003SEP078_0098_01_NULL_01_p16.mld,ROHM",
            "0_2003SEP078_0098_01_NULL_01_p40.mld,FUETREK",
            "0_2003SEP078_0098_01_NULL_01_d16.mld,ROHM",
            "0_2003SEP078_0098_01_NULL_01_d40.mld,FUETREK",
            "/foo/bar/0_2003SEP078_0098_01_NULL_01_SH40.MLD,ROHM",
            "20143D503i.mld,ROHM",
            "20143D503iS.mld,ROHM",
            "20143D2101V.mld,ROHM",
            "20143D504i.mld,ROHM",
            "/foo/bar/20143n2701v.MLD,YAMAHA",
            "20143SO505iS.mld,FUETREK",
    })
    void testFile(String file, String chip) throws Exception {
        Condition condition = new Condition(List.of(), null, Set.of(), -1, -1, file);
        Detection detection = MfiChip.detect(condition);
Debug.print("reason: " + detection.reason());
        assertEquals(MfiChip.valueOf(chip), detection.chip());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "D503i.mld,D503I",
            "20143D503iS.mld,D503I",
            "20143SO213iS.mld,SO213IS",
            "20143D2101V.mld,D2101V",
            "20143SO505iS.mld,SO505IS",
            "20143D503i_01.mld,D503I",
            "20143D503i01.mld,",
            "20143D502i.mld,",
            "N2101V.mld,",
            "0_2003SEP078_0098_01_NULL_01_n40.mld,",
    })
    void testModelInName(String name, String model) throws Exception {
        assertEquals(model, MfiChip.modelInName(name));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "/Users/nsano/Public/np2/mfi/mld-ddd/mld_data0401141711/20143D503i.mld,ROHM",
            "/Users/nsano/Public/np2/mfi/mld-ddd/mld_data0401141711/20143D503iS.mld,ROHM",
            "/Users/nsano/Public/np2/mfi/mld-ddd/mld_data0401141711/20143D2101V.mld,ROHM",
            "/Users/nsano/Public/np2/mfi/upload_melody_2003-10-02-18-55-37-000-00000/0_2003SEP078_0098_01_NULL_01_n40.mld,YAMAHA",
            "/Users/nsano/Public/np2/mfi/upload_melody_2003-10-02-18-55-37-000-00000/0_2003SEP078_0098_01_NULL_01_n16.mld,YAMAHA",
            "/Users/nsano/Public/np2/mfi/upload_melody_2003-10-02-18-55-37-000-00000/0_2003SEP078_0098_01_NULL_01_f40.mld,ROHM",
    })
    void testFileReal(String mld, String chip) throws Exception {
        Path path = Paths.get(mld);
        Sequence seq = MidiSystem.getSequence(new BufferedInputStream(Files.newInputStream(path)));

        Condition condition = Condition.create(seq, mld);
Debug.print(condition);
        Detection detection = MfiChip.detect(condition);
Debug.print("reason: " + detection.reason());
        assertEquals(MfiChip.valueOf(chip), detection.chip());
    }
}
