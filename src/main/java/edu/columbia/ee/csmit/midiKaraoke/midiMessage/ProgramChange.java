/*
 * ProgramChange.java
 *
 * Created on Dec 2, 2007, 11:17:31 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with a program change command.
 *
 * @author Christine
 * @see ProgramChangeParser
 */
public interface ProgramChange extends MidiCommandWithChannel {

    /**
     * Returns the new program number for this program change command.
     *
     * @return the new program number
     */
    int getProgramNumber();
}
