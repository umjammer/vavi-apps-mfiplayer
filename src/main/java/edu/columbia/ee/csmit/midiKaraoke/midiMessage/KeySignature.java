/*
 * KeySignature.java
 *
 * Created on Nov 1, 2007, 11:41:57 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with a key signature command.
 *
 * @author Christine
 * @see KeySignatureParser
 */
public interface KeySignature extends MetaCommand {

    /**
     * Returns the number of sharps or flats in the key signature. -7 indicates
     * seven flats, 0 is no sharps or flats, and 7 indicates seven sharps.  (The
     * order of sharps is f-c-g-d-a-e-b.  The order of flats is b-e-a-d-g-c-f.)
     *
     * @return number of sharps or flats
     */
    int getSharpsFlats();

    /**
     * Returns true if this is a major key and false if the key is minor.
     *
     * @return true if major key
     */
    boolean isMajor();
}
