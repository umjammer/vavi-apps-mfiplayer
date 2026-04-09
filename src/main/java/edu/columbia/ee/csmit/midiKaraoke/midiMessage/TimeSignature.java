/*
 * TimeSignature.java
 *
 * Created on Nov 1, 2007, 7:23:30 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores the information associated with a time signature command.
 *
 * @author Christine
 * @see TimeSignatureParser
 */
public interface TimeSignature extends MetaCommand {

    /**
     * Returns the numerator of the time signature.  6 in 6/8 time, for example.
     *
     * @return the numerator
     */
    int getNumerator();

    /**
     * Returns the denominator of the time signature, where 2 is quarter, 3 is
     * eighth, etc.
     *
     * @return the denominator
     */
    int getDenominator();

    /**
     * Return the number of midi clocks in a metronome click.
     *
     * @return ticks
     */
    int getMetronomeClick();

    /**
     * Returns the "number of notated 32nd notes in a midi quarter note" (stolen
     * from <A href=http://www.borg.com/~jglatt/tech/midifile/time.htm>this</A> site).
     *
     * @return 32nd notes in a midi quarter note
     */
    int getMidiQuarterNote();
}
