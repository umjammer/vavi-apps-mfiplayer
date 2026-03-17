/*
 * MidiCommandWithChannelParser.java
 *
 * Created on Nov 7, 2007, 10:09:54 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses midi commands with a channel.  Does not interpret the commands any
 * further.  Simply pulls out the channel number.
 *
 * @author Christine
 * @see MidiCommandWithChannel
 */
public class MidiCommandWithChannelParser extends MidiCommandParser {


    public MidiCommandWithChannelParser() {
        // nothing to do here
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        // note we aren't checking anything here because it might not be clear
        // what is a channel-based message and what isn't.  This parser is more 
        // here for form than to be actually used.
        return new Command(mm.getMessage());
    }

    public static class Command
            extends MidiCommandParser.Command
            implements MidiCommandWithChannel {

        public Command(byte[] message) {
            super(message);
        }

        @Override
        public int getChannel() {
            return message[0] & 0x0F;
        }

        @Override
        public String toString() {
            return "Unknown midi message: " + toHex(message);
        }
    }

}
