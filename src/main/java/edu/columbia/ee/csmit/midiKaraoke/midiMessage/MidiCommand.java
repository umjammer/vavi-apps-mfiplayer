/*
 * ParsedMidiMessage.java
 *
 * Created on Nov 1, 2007, 6:42:51 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with midi message commands.
 *
 * @author Christine
 * @see MidiCommandParser
 */
public interface MidiCommand {

    /**
     * Returns the length of the raw message in bytes.
     *
     * @return the length of the message
     */
    int getLength();

    /**
     * Returns the raw message bytes.
     *
     * @return the message
     */
    byte[] getMessage();
}