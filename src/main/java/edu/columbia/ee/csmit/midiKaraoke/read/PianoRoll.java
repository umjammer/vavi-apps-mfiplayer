/*
 * PianoRoll.java
 *
 * Created on Nov 8, 2007, 11:08:52 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Stores the notes in a midi sequence as a list of sequential notes.
 *
 * @author Christine
 * @see PianoRollViewParser
 */
public interface PianoRoll {

    /**
     * Returns the notes in this piano roll.  The notes are ordered by each
     * note's start time.  The track number is included in each note, so you can
     * separate out the notes by track.
     *
     * @return the notes
     */
    NotesInMidi[] getNotes();

    /**
     * Returns the notes as a matrix.  Each row is one note.  The columns are as
     * follows:<br>
     * 0 - note value (pitch)<br />
     * 1 - note velocity<br />
     * 2 - note channel<br />
     * 3 - note start (ticks)<br />
     * 4 - note duration (ticks)<br />
     * 5 - note start (seconds)<br />
     * 6 - note duration (seconds)<br />
     * 7 - note track<br />
     *
     * @return the notes in this piano roll
     */
    double[][] getNotesDoubles();

}
