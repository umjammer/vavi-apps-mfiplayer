/*
 * SetTempoParser.java
 *
 * Created on Nov 7, 2007, 11:28:37 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a set tempo command.
 *
 * @author Christine
 * @see SetTempo
 */
public class SetTempoParser extends MetaCommandParser {

    public SetTempoParser() {
        // no need to initialize
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT &&
                message[1] == RawMidiMessageParser.SET_TEMPO) {

            int val = 0;

            for (int i = 0; i < 3; i++) {
                val = val << 8;
                val = (val & 0xFFFFFF00) | (0xFF & message[3 + i]);
            }

            return new Command(message, val);
        } else {
            return null;
        }
    }


    public static class Command extends
            MetaCommandParser.Command implements SetTempo {

        private int microsecondsPerQuarterNote = 0;

        public Command(byte[] message,
                       int microsecondsPerQuarterNote) {
            super(message);
            this.microsecondsPerQuarterNote = microsecondsPerQuarterNote;
        }

        @Override
        public int getMicrosecondsPerQuarterNote() {
            return microsecondsPerQuarterNote;
        }

        @Override
        public String toString() {
            return "Set Tempo: " + microsecondsPerQuarterNote +
                    " microseconds/quarter note";
        }
    }

}
