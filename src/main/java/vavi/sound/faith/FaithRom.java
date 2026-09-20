/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.faith;

import java.io.File;


/**
 * FaithRom.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
public class FaithRom {

    /** the dll itself, which is the whole of what is taken from that directory */
    public static final String DLL = "rt_synth_4.dll";

    /** where the authoring tool's {@code Tools} directory is */
    public static final String PATH_KEY = "vavi.sound.faith.path";

    /** the authoring tool's {@code Tools} directory, which is where the dll lives */
    public static File toolsDirectory() {
        return new File(System.getProperty(PATH_KEY, System.getProperty("user.home")
                + "/.wine/drive_c/Program Files (x86)/Faith/Ring Tone Authoring Tool/Tools"));
    }

    /** is there a Type 4 synthesizer to play with? */
    public static boolean isAvailable() {
        return new File(FaithRom.toolsDirectory(), DLL).exists();
    }
}
