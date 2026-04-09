/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 *
 * @author Christine
 */
public class PitchWheelChangeParser extends MidiCommandWithChannelParser {

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        byte upper = (byte) (message[0] & 0xF0);

        if (upper == RawMidiMessageParser.PITCH_WHEEL_CHANGE) {
            return new Command(message, message[1], message[2]);
        } else {
            return null;
        }
    }

    private static class Command extends MidiCommandWithChannelParser.Command implements PitchWheelChange {

        private final int value;

        public Command(byte[] message, byte lowerByte, byte upperByte) {
            super(message);
            // The lower byte actually has the least significant 7 bits and the
            // upper byte has the most significant 7 bits.
            value = (lowerByte & 0x7F) + (((int) upperByte << 7) & 0x3F80);
        }

        @Override
        public int getValue() {
            return value;
        }

    }

}
