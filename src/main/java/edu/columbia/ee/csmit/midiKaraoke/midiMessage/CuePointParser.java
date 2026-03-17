/*
 * CuePointParser.java
 *
 * Created on Nov 7, 2007, 10:55:30 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a cue point meta command.
 *
 * @author Christine
 * @see CuePoint
 */
public class CuePointParser extends MetaCommandParser {

    public CuePointParser() {
        // nothing here to do
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT
                && message[1] == RawMidiMessageParser.CUE_POINT) {
            return new Command(message, parseString(message, 2));
        } else {
            return null;
        }

    }

    private static class Command extends MidiCommandWithChannelParser.Command {

        private final String text;

        public Command(byte[] message, String text) {
            super(message);
            this.text = text;
        }

        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return "Cue point (channel " + getChannel() + "): " +
                    text;
        }
    }
}
