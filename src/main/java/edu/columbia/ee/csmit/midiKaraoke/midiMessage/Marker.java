/*
 * Marker.java
 *
 * Created on Nov 1, 2007, 7:00:51 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores all the information associated with a Marker midi command.
 *
 * @author Christine
 * @see MarkerParser
 */
public interface Marker extends MetaCommand {

    /**
     * Returns the text in this command.
     *
     * @return the command text.
     */
    String getText();
}
