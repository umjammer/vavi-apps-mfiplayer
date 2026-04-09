/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.examples;

import java.io.File;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.ControlChange;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.Lyric;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.MidiCommand;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.NoteOn;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.RawMidiMessageParser;


/**
 * Shows how to use the {@link RawMidiMessageParser}.  The RawMidiMessageParser
 * basically can parse a single {@link MidiCommand}, which might be a
 * {@link NoteOn}, {@link ControlChange}, {@link Lyric}, etc.
 *
 * <blockquote><pre>
 * public static void main(String[] args) {
 *
 * if(args.length == 0){
 * System.out.println("Usage: RawParserExample [midi file name]");
 * return;
 * }
 *
 *
 * try {
 *
 * File file = new File(args[0]);
 * Sequence mySeq = MidiSystem.getSequence(file);
 *
 * RawMidiMessageParser parser = new RawMidiMessageParser();
 *
 * Track[] tracks = mySeq.getTracks();
 *
 * for (int i = 0; i < tracks.length; i++) {
 * for (int j = 0; j < tracks[i].size(); j++) {
 * MidiEvent me = tracks[i].get(j);
 * MidiCommand mc = parser.parse(me.getMessage());
 *
 * System.out.println(i + "(" + me.getTick() + "): " + mc);
 * }
 * }
 *
 *
 * } catch (Exception e) {
 * System.out.println("Problem!");
 * e.printStackTrace();
 * System.out.println(e.toString());
 * }
 *
 * }
 *
 * </blockquote></pre>
 *
 * @author Christine
 */
public class RawParserExample {

    /**
     * Shows how to use the {@link RawMidiMessageParser} to parse midi commands
     * in a {@link Sequence}.  Prints out the midi commands it reads.
     *
     * @param args the command line arguments.  The first (and only) argument
     *             should be the name of a midi file.
     */
    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("Usage: RawParserExample [midi file name]");
            return;
        }


        try {

            File file = new File(args[0]);
            Sequence mySeq = MidiSystem.getSequence(file);

            RawMidiMessageParser parser = new RawMidiMessageParser();

            Track[] tracks = mySeq.getTracks();

            for (int i = 0; i < tracks.length; i++) {
                for (int j = 0; j < tracks[i].size(); j++) {
                    MidiEvent me = tracks[i].get(j);
                    MidiCommand mc = parser.parse(me.getMessage());

                    System.out.println(i + "(" + me.getTick() + "): " + mc);
                }
            }


        } catch (Exception e) {
            System.out.println("Problem!");
            e.printStackTrace();
            System.out.println(e);
        }

    }

}
