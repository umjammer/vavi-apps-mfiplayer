/*
 * Note.java
 *
 * Created on Nov 8, 2007, 11:09:58 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Stores the information associated with a midi note.
 *
 * @author Christine
 */
public interface TimedNote extends TrackMidi, ChannelMidi {

    /**
     * Returns the midi number associated with the pitch of this note.
     *
     * @return the note
     */
    int getNote();

    /**
     * Returns the velocity of this note.
     *
     * @return the velocity.
     */
    int getVelocity();

    /**
     * Returns the tick number of the start of this note.  The tick is in
     * fractions of a quarter note.
     *
     * @return the note start tick
     * @see #getDurationTick()
     * @see javax.sound.midi.Sequence#getResolution()
     */
    long getStartTick();

    /**
     * Returns the number of ticks in the note.
     *
     * @return the note duration tick
     * @see #getStartTick()
     */
    long getDurationTick();

    /**
     * Returns the start time of the note in seconds.
     *
     * @return the note start time
     * @see #getDurationSeconds()
     */
    double getStartSeconds();

    /**
     * Returns the note duration in seconds.
     *
     * @return the note duration in seconds
     * @see #getStartSeconds()
     */
    double getDurationSeconds();

    /**
     * Returns the channel for this note.
     *
     * @return the channel
     */
    @Override
    int getChannel();

}
