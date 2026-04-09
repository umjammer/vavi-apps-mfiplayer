/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.write;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.RawMidiMessageParser;
import edu.columbia.ee.csmit.midiKaraoke.read.SequenceDivisionTypeException;


/**
 * Allows you to manipulate the channels in a midi file.
 *
 * @author Christine
 */
public class ChannelSpecifier {

    /**
     * Reads in a midi file, filters out note on/note off commands that aren't
     * in the desired channel(s), and then writes the result to a new midi file.
     *
     * @param originalFileName name of the original midi file
     * @param newFileName      name of the destination midi file
     * @param keptChannels     array of the channels you want to keep
     * @throws InvalidMidiDataException
     * @throws IOException
     * @throws SequenceDivisionTypeException
     */
    public static void write(String originalFileName, String newFileName,
                             int[] keptChannels) throws InvalidMidiDataException, IOException, SequenceDivisionTypeException {

        File myMidiFile = new File(originalFileName);
        Sequence seq = MidiSystem.getSequence(myMidiFile);

        // Check to make sure that this is the kind of sequence we want
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new SequenceDivisionTypeException();
        }

        // get the tracks out
        Track[] tracks = seq.getTracks();
        LinkedList<MidiEvent> eventsToRemove = new LinkedList<>();
        for (Track track : tracks) {
            for (int j = 0; j < track.size(); j++) {
                MidiEvent me = track.get(j);
                MidiMessage mm = me.getMessage();
                byte[] bytes = mm.getMessage();

                // we just want note on and note off
                byte upperfour = (byte) (bytes[0] & RawMidiMessageParser.UPPER_NIBBLE);
                if (upperfour == RawMidiMessageParser.NOTE_OFF ||
                        upperfour == RawMidiMessageParser.NOTE_ON) {
                    // okay - this is a command, therefore it has a channel
                    int channel = bytes[0] & 0x0F;
                    //System.out.format("CMD: 0x%x, CH: %d\n", upperfour,channel);

                    // now see if this channel matches one of the channels we
                    // are going to keep
                    boolean removeEvent = true;
                    for (int keptChannel : keptChannels) {
                        if (channel == keptChannel) {
                            removeEvent = false;
                        }
                    }

                    // okay remove the event if it is not on the channel we
                    // want
                    if (removeEvent) {

                        eventsToRemove.add(me);
                    }

                }

            }
            // now remove all the events in this track that are not on the
            // correct channel

            for (MidiEvent midiEvent : eventsToRemove) {
                track.remove(midiEvent);
            }
            eventsToRemove.clear();
        }

        // now that we have removed the events on channels we don't want,
        // write out the sequence to the new file
        File outfile = new File(newFileName);
        MidiSystem.write(seq, 1, outfile);
    }
}
