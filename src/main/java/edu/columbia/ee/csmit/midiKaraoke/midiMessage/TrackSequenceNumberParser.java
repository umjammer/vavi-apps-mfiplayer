/*
 * TrackSequenceNumberParser.java
 *
 * Created on Nov 7, 2007, 11:58:24 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a track sequency number command.
 *
 * @author Christine
 * @see TrackSequenceNumber
 */
public class TrackSequenceNumberParser extends MetaCommandParser {

    public TrackSequenceNumberParser() {
        // nothing to do
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT
                && message[1] == RawMidiMessageParser.SET_TRACK_SEQUENCE_NUMBER) {

            int upperByte = message[3];
            int lowerByte = message[4];

            int number = 0;

            number = number | (upperByte & 0xFF);
            number = number << 8;

            number = number | (lowerByte & 0xFF);

            return new Command(message, number);
        } else {
            return null;
        }
    }


    private static class Command extends
            MetaCommandParser.Command implements
            TrackSequenceNumber {

        final int trackSequenceNumber;

        public Command(byte[] message,
                       int trackSequenceNumber) {
            super(message);
            this.trackSequenceNumber = trackSequenceNumber;
        }

        @Override
        public int getTrackSequenceNumber() {
            return trackSequenceNumber;
        }

        @Override
        public String toString() {
            return "Track Sequence Number: " + trackSequenceNumber;
        }
    }
}
