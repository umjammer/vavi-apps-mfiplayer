/*
 * TrackInstrumentName.java
 *
 * Created on Nov 1, 2007, 6:56:47 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with the track instrument name command.
 *
 * @author Christine
 * @see TrackInstrumentNameParser
 */
public interface TrackInstrumentName extends MetaCommand {

    /**
     * Returns the track instrument's name.
     *
     * @return the instrument name
     */
    String getName();
}
