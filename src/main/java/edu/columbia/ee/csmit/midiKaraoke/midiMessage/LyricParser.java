/*
 * LyricParser.java
 *
 * Created on Nov 7, 2007, 11:11:23 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a lyric meta command.
 *
 * @author Christine
 * @see Lyric
 */
public class LyricParser extends MetaCommandParser {

    public LyricParser() {
        // nothing to do
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        if (message[0] == RawMidiMessageParser.META_EVENT
                && message[1] == RawMidiMessageParser.LYRIC) {

            // first, get the raw bytes of just the text out
            // The first byte is the META_EVENT code.  The second byte is
            // the LYRIC code.  The third byte is the number of bytes in
            // the lyric.
            byte[] rawBytes = new byte[message.length - 3];
            for (int i = 0; i < rawBytes.length; i++) {
                rawBytes[i] = message[i + 3];
            }
            // create the new command
            return new Command(message, parseString(message, 2), rawBytes);
        } else {
            return null;
        }
    }

    private static class Command extends
            MetaCommandParser.Command implements Lyric {

        private String lyric = null;
        private byte[] rawBytes = null;

        public Command(byte[] message, String lyric, byte[] rawBytes) {
            super(message);
            this.lyric = lyric;
            this.rawBytes = rawBytes;
        }

        @Override
        public String getText() {
            return lyric;
        }

        @Override
        public String toString() {
            return "Lyric: " + lyric;
        }

        @Override
        public byte[] getTextBytes() {
            return rawBytes;
        }
    }


}
