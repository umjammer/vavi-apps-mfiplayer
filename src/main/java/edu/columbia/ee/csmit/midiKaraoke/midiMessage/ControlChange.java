/*
 * ControlChange.java
 *
 * Created on Dec 2, 2007, 11:00:13 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with a control change command.
 *
 * @author Christine
 * @see ControlChangeParser
 */
public interface ControlChange extends MidiCommand {

    /**
     * Returns the controller number for this command.
     *
     * @return the controller number
     */
    int getControllerNumber();

    /**
     * Returns the new value for the controller.
     *
     * @return the new value
     */
    int getNewValue();

}
