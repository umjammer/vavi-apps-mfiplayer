/*
 * ProgramChangeParser.java
 *
 * Created on Dec 2, 2007, 11:19:31 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a program change command.
 *
 * @author Christine
 * @see ProgramChange
 */
public class ProgramChangeParser extends MidiCommandWithChannelParser {

    public ProgramChangeParser() {
        // nothing to do here
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        byte upper = (byte) (message[0] & 0xF0);

        if (upper == RawMidiMessageParser.PROGRAM_CHANGE) {
            return new Command(message, message[1]);
        } else {
            return null;
        }
    }

    private static class Command extends MidiCommandWithChannelParser.Command implements ProgramChange {

        private final int programNumber;

        public Command(byte[] message, int programNumber) {
            super(message);
            this.programNumber = programNumber;
        }

        @Override
        public int getProgramNumber() {
            return programNumber;
        }


        @Override
        public String toString() {
            return "Program Change (channel " + getChannel() + "): "
                    + "program number " + programNumber;
        }
    }
}