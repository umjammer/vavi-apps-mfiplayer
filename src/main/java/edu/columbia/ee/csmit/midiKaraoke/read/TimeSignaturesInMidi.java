/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Stores all the time signatures for a particular sequence in time order.
 *
 * @author Christine
 */
public interface TimeSignaturesInMidi {

    /**
     * Gets the time signature(s) for the sequence.
     *
     * @return the time signatures.
     */
    TimeSignatureInTrack[] getTimeSignatures();

    /**
     * Get the time signature information.
     * <p>
     * Columns:
     * 0 - numerator
     * 1 - denominator
     * 2 - metronome click
     * 3 - midi quarter note
     * 4 - time in ticks
     * 5 - time in seconds
     * 6 - track number
     *
     * @return the time signature information as an array of doubles
     */
    double[][] getTimeSignaturesDoubles();

}
