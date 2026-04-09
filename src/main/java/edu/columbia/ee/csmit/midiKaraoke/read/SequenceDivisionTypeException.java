/*
 * SequenceDivisionTypeException.java
 *
 * Created on Nov 8, 2007, 11:40:03 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Indicates that a midi {@link javax.sound.midi.Sequence} cannot be parsed
 * using this package because it has teh wrong division type.
 *
 * @author Christine
 */
public class SequenceDivisionTypeException extends Exception {

    public SequenceDivisionTypeException() {
        super("Sequence division type must be of type PPQ.");
    }
}
