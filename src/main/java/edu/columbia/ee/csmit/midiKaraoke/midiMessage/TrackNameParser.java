/*
 * TrackNameParser.java
 *
 * Created on Nov 7, 2007, 11:54:37 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a track name command.
 *
 * @author Christine
 * @see TrackName
 */
public class TrackNameParser extends MetaCommandParser {

    public TrackNameParser() {
        // nothing to do here
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT
                && message[1] == RawMidiMessageParser.SEQUENCE_OR_TRACK_NAME) {
            return new Command(message, parseString(message, 2));
        } else {
            return null;
        }
    }


    private static class Command extends
            MetaCommandParser.Command implements TrackName {

        private String name = null;

        public Command(byte[] message, String name) {
            super(message);
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return "Sequence or Track Name: " + name;
        }
    }

}
