/*
 * NoteOff.java
 *
 * Created on Nov 1, 2007, 6:46:03 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with a note off command.  Apparently, a note
 * on command with velocity zero can also be used to turn notes off.
 *
 * @author Christine
 * @see NoteOn
 * @see NoteOffParser
 */
public interface NoteOff extends MidiCommandWithChannel {

    /**
     * Returns the note number associated with this note off command.  C4 is 60,
     * C#4 is 61, etc.
     *
     * @return the note number
     */
    int getNoteNumber();

    /**
     * Returns the midi note velocity.
     *
     * @return the note velocity
     */
    int getVelocity();
}
