/*
 * MidiCommandParser.java
 *
 * Created on Nov 7, 2007, 10:03:06 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a generic midi command.  Does not attempt to interpret the command
 * message.
 *
 * @author Christine
 * @see MidiCommand
 */
public class MidiCommandParser implements RawMidiMessageParser.Parser {

    public MidiCommandParser() {
        // nothing to do here
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        return new Command(mm.getMessage());
    }

    /**
     * Returns a string extracted from a raw midi message.  This is a helper
     * function for the many midi commands that contain strings.
     *
     * @param message the raw message
     * @param offset  the index of the string length
     * @return the string in the midi message
     */
    protected static String parseString(byte[] message, int offset) {
        StringBuilder sb = new StringBuilder();


        int numChars = message[offset];

        for (int i = offset + 1; i < offset + 1 + numChars; i++) {

            // see if this is an ascii-printable character
            if (message[i] > 31 && message[i] < 127) {
                sb.append((char) message[i]);
            } else {
                sb.append("[0x" + Integer.toHexString(message[i]) + "]");
            }

        }
        return sb.toString();
    }

    public static class Command implements MidiCommand {

        protected byte[] message = null;

        public Command(byte[] message) {
            this.message = message;
        }

        @Override
        public int getLength() {
            return message.length;
        }

        @Override
        public byte[] getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "Unknown midi message: " + toHex(message);
        }


        protected static String toHex(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length - 1; i++) {
                sb.append(toHex(b[i]) + " ");
            }
            sb.append(toHex(b[b.length - 1]));
            return sb.toString();
        }

        protected static String toHex(byte b) {
            int upper = (b & 0xF0) >> 4;
            int lower = b & 0x0F;

            return String.format("%1$X%2$X", upper, lower);
        }

    }
}