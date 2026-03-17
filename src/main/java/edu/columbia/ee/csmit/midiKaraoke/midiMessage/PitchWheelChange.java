/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores a pitch bend or wheel change command.
 *
 * @author Christine
 */
public interface PitchWheelChange extends MidiCommandWithChannel {

    /**
     * Values above NO_CHANGE bend the pitch up and values below bend the pitch
     * down.
     */
    int NO_CHANGE = 0x2000;

    /**
     * Gets the pitch change value,a 14-bit value which ranges from 0x0000 to
     * 0x3FFF.  A value of {@link PitchWheelChange#NO_CHANGE} indicates that the
     * pitch should be centered or standard.
     *
     * @return the pitch change.  What this value means can apparently be
     * different for different vendors.
     */
    int getValue();
}
