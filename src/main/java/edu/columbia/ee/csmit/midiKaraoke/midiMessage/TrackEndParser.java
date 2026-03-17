/*
 * TrackEndParser.java
 *
 * Created on Nov 7, 2007, 11:44:03 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses and end of track command.
 *
 * @author Christine
 * @see TrackEnd
 */
public class TrackEndParser extends MetaCommandParser {

    public TrackEndParser() {
        // nothing to do here
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT
                && message[1] == RawMidiMessageParser.TRACK_END) {
            return new Command(message);
        } else {
            return null;
        }
    }


    private static class Command extends MetaCommandParser.Command
            implements TrackEnd {

        public Command(byte[] message) {
            super(message);
        }

        @Override
        public String toString() {
            return "Track End";
        }

    }

}
