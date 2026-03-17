/*
 * KeySignatureParser.java
 *
 * Created on Nov 7, 2007, 11:07:20 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a key signature meta command.
 *
 * @author Christine
 * @see KeySignature
 */
public class KeySignatureParser extends MetaCommandParser {

    public KeySignatureParser() {
        // nothing to do here
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT
                && message[1] == RawMidiMessageParser.KEY_SIGNATURE) {

            if (message[4] == 0) {
                return new Command(message, message[3], true);
            } else {
                return new Command(message, message[3], false);
            }

        } else {
            return null;
        }

    }


    private static class Command extends
            MetaCommandParser.Command implements KeySignature {

        private int sharpsFlats = 0;
        private final boolean major;

        public Command(byte[] message, int sharpsFlats,
                       boolean major) {
            super(message);
            this.sharpsFlats = sharpsFlats;
            this.major = major;
        }

        @Override
        public int getSharpsFlats() {
            return sharpsFlats;
        }

        @Override
        public boolean isMajor() {
            return major;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            sb.append("Key Signature: ");
            if (major) {
                switch (sharpsFlats) {
                    case (-7):
                        sb.append("Cb");
                        break;
                    case (-6):
                        sb.append("Gb");
                        break;
                    case (-5):
                        sb.append("Db");
                        break;
                    case (-4):
                        sb.append("Ab");
                        break;
                    case (-3):
                        sb.append("Eb");
                        break;
                    case (-2):
                        sb.append("Bb");
                        break;
                    case (-1):
                        sb.append("F");
                        break;
                    case (0):
                        sb.append("C");
                        break;
                    case (1):
                        sb.append("G");
                        break;
                    case (2):
                        sb.append("D");
                        break;
                    case (3):
                        sb.append("A");
                        break;
                    case (4):
                        sb.append("E");
                        break;
                    case (5):
                        sb.append("B");
                        break;
                    case (6):
                        sb.append("F#");
                        break;
                    case (7):
                        sb.append("C#");
                        break;
                }
                sb.append(" Major");
            } else {
                switch (sharpsFlats) {
                    case (-7):
                        sb.append("Ab");
                        break;
                    case (-6):
                        sb.append("Eb");
                        break;
                    case (-5):
                        sb.append("Bb");
                        break;
                    case (-4):
                        sb.append("Fb");
                        break;
                    case (-3):
                        sb.append("C");
                        break;
                    case (-2):
                        sb.append("G");
                        break;
                    case (-1):
                        sb.append("D");
                        break;
                    case (0):
                        sb.append("A");
                        break;
                    case (1):
                        sb.append("E");
                        break;
                    case (2):
                        sb.append("B");
                        break;
                    case (3):
                        sb.append("F#");
                        break;
                    case (4):
                        sb.append("C#");
                        break;
                    case (5):
                        sb.append("G#");
                        break;
                    case (6):
                        sb.append("D#");
                        break;
                    case (7):
                        sb.append("A#");
                        break;
                }
                sb.append(" Minor");
            }
            return sb.toString();
        }
    }


}
