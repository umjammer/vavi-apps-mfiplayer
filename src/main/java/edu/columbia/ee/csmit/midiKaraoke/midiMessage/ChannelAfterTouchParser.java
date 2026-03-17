/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import javax.sound.midi.MidiMessage;


/**
 * Parses a Channel after-touch command
 *
 * @author Christine
 */
public class ChannelAfterTouchParser extends MidiCommandWithChannelParser {

    public ChannelAfterTouchParser() {
        // nothing to do...
    }

    @Override
    public MidiCommand parse(MidiMessage mm) {
        byte[] message = mm.getMessage();

        byte upper = (byte) (message[0] & 0xF0);

        if (upper == RawMidiMessageParser.CHANNEL_AFTER_TOUCH) {
            return new Command(message, message[1]);
        } else {
            return null;
        }
    }

    private static class Command extends MidiCommandWithChannelParser.Command implements ChannelAfterTouch {

        private final byte channel;

        public Command(byte[] message, byte channel) {
            super(message);
            this.channel = channel;
        }

        @Override
        public byte getChannelNumber() {
            return channel;
        }

    }
}
