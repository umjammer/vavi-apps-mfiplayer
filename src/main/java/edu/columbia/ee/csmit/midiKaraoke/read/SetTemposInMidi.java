/*
 * TempoRoll.java
 *
 * Created on Nov 25, 2007, 5:16:50 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Stores all the tempo changes for a particular sequence in time order.  It's
 * the same idea as {@link PianoRoll PianoRoll}, but for tempo changes.
 *
 * @author Christine
 * @see SetTempoViewParser
 */
public interface SetTemposInMidi {

    /**
     * Returns the tempos in the order in which they were read.
     *
     * @return the tempos
     */
    SetTempoInTrack[] getTempos();

    /**
     * Returns the tempos in an array format.
     * <p>
     * columns:
     * 0 - microseconds per quarter note
     * 1 - time in ticks
     * 2 - time in seconds
     * 3 - track number
     *
     * @return tempo change information
     */
    double[][] getTemposDoubles();
}
