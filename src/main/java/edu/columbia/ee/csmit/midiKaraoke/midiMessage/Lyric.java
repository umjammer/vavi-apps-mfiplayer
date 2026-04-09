/*
 * Lyric.java
 *
 * Created on Oct 19, 2007, 9:49:56 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores a lyric meta command.
 *
 * @author Christine
 * @see LyricParser
 */
public interface Lyric extends MetaCommand {

    /**
     * Returns the text associated with this command.  I have found in .kar
     * files that "text-" means that this is one syllable in a multiple-syllable
     * word.  In other words, if "hello" is separated across different notes,
     * you will probably get one lyric command with "he-" and one with "llo".
     *
     * @return the lyric
     */
    String getText();

    /**
     * Returns the raw binary bytes that contain the ascii-encoded characters.
     *
     * @return the raw ascii for the text
     */
    byte[] getTextBytes();
}
