/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Denotes a midi event on a track.
 *
 * @author Christine
 */
public interface TrackMidi {

    /**
     * Returns the track number associated with this note.
     *
     * @return the track number
     */
    int getTrackNumber();

    /**
     * Returns the time in seconds at which this tempo change occurs.
     *
     * @return the time in seconds for this tempo change
     */
    double getSeconds();

    /**
     * Returns the time in ticks at which this tempo change occurs.
     *
     * @return the time in ticks for this tempo change
     */
    long getTicks();
}
