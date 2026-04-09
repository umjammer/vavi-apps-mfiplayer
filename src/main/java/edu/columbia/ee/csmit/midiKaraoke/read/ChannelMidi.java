/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

/**
 * Stores the channel information for midi commands.
 *
 * @author Christine
 */
public interface ChannelMidi {

    /**
     * Gets the channel associated with this midi.
     *
     * @return the channel (0x0-0xF)
     */
    int getChannel();

}
