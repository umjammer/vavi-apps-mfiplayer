/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.examples;

import java.io.File;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.read.NotesInMidi;
import edu.columbia.ee.csmit.midiKaraoke.read.PianoRoll;
import edu.columbia.ee.csmit.midiKaraoke.read.PianoRollViewParser;


/**
 * Shows how to get a {@link PianoRoll} from a midi file.
 *
 * <blockquote><pre>
 * public static void main(String[] args) {
 *
 *   if (args.length == 0) {
 *     System.out.println("Usage: PianoRollExample [midi file name]");
 *     return;
 *   }
 *
 *   try {
 *     File file = new File(args[0]);
 *     Sequence mySeq = MidiSystem.getSequence(file);
 *     PianoRoll roll = PianoRollViewParser.parse(mySeq);
 *     NotesInMidi[] notes = roll.getNotes();
 *     for (int i = 1; i &lt notes.length; i++) {
 *       System.out.println(notes[i].toString());
 *     }
 *   } catch (Exception e) {
 *     System.out.println("Problem!");
 *     e.printStackTrace();
 *     System.out.println(e.toString());
 *   }
 * }
 * </pre></blockquote>
 *
 * @author Christine
 */
public class PianoRollExample {

    /**
     * Shows how to use the {@link PianoRollViewParser} to parse midi commands
     * in a {@link PianoRoll}.  Prints out the notes it read.
     *
     * @param args the command line arguments.  The first (and only) argument
     *             should be the name of a midi file.
     */
    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("Usage: PianoRollExample [midi file name]");
            return;
        }

        try {
            File file = new File(args[0]);
            Sequence mySeq = MidiSystem.getSequence(file);
            PianoRoll roll = PianoRollViewParser.parse(mySeq);
            NotesInMidi[] notes = roll.getNotes();
            for (int i = 1; i < notes.length; i++) {
                System.out.println(notes[i].toString());
            }
        } catch (Exception e) {
            System.out.println("Problem!");
            e.printStackTrace();
            System.out.println(e);
        }
    }
}
