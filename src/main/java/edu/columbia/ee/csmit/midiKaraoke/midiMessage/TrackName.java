/*
 * TrackName.java
 *
 * Created on Nov 1, 2007, 6:54:17 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the track name.
 *
 * @author Christine
 * @see TrackNameParser
 */
public interface TrackName extends MetaCommand {

    /**
     * Returns this track's name.
     *
     * @return the track name
     */
    String getName();
}
