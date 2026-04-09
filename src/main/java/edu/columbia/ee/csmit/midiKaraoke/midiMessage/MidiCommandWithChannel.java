/*
 * ParsedMidiCommandWithChannel.java
 *
 * Created on Nov 7, 2007, 10:19:32 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Interface for midi commands that have a channel.
 *
 * @author Christine
 * @see MidiCommandWithChannelParser
 */
public interface MidiCommandWithChannel extends MidiCommand {

    int getChannel();
}
