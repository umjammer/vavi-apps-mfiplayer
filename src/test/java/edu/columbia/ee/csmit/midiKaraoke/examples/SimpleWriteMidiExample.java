/*
To change this template, choose Tools | Templates
and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.examples;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.read.NotesInMidi;
import edu.columbia.ee.csmit.midiKaraoke.read.PianoRoll;
import edu.columbia.ee.csmit.midiKaraoke.read.PianoRollViewParser;
import edu.columbia.ee.csmit.midiKaraoke.read.ProgramChangeInTrack;
import edu.columbia.ee.csmit.midiKaraoke.read.ProgramChangeViewParser;
import edu.columbia.ee.csmit.midiKaraoke.read.ProgramChangesInMidi;
import edu.columbia.ee.csmit.midiKaraoke.read.SequenceDivisionTypeException;
import edu.columbia.ee.csmit.midiKaraoke.write.SimpleMidiWriter;
import edu.columbia.ee.csmit.midiKaraoke.write.SimpleMidiWriter.InvalidSignature;
import edu.columbia.ee.csmit.midiKaraoke.write.SimpleMidiWriter.InvalidTempo;


/**
 * Shows how to use the  {@link SimpleMidiWriter}.
 *
 * <blockquote><pre>
 * public static void main(String[] args) throws InvalidMidiDataException,
 * InvalidTempo, IOException, InvalidSignature {
 *
 * // define the onset of each note in beats
 * double[] onset = {0, 2, 5};
 * // make each note 2 beats long
 * double[] duration = {2, 2, 2};
 * // pitches C4 (middle C), D4, E4
 * int[] pitch = {60, 62, 64};
 * // put each note on a different channel
 * int[] channel = {0, 1, 2};
 * // use a variety of velocity (loudness) values
 * int[] velocity = {40,65, 90};
 * // put all the notes on track 1
 * int[] track = {1, 1, 1};
 * //  channel  |   track   |   patch number:
 * //     0           1               0  (acoustic grand piano)
 * //     1           1               56 (trumpet)
 * //     2           1               22 (harmonica)
 * int[][] patches = {{0,1,0},{1,1,56},{2,1,22}};
 * // pick half a second per quarter note
 * int microsecondsPerQuarterNote = (int) (0.5 * 1000000.0);
 * // pick a resolution of 100 ticks per beat.  This means that the
 * // shortest note we can specify is 1/100th of a beat.
 * int resolution = 100;
 * // pick a time signature, 4/4 (common time) with 1 midi clock per
 * // quarter note and 8 32nd notes per quarter note.
 * int[] timeSignature = {4,4,1,8};
 * // pick some name for the resulting file.
 * String fileName = "Temp.mid";
 *
 * // write the file
 * SimpleMidiWriter.write(fileName, onset, duration, channel, pitch,
 * velocity, track, microsecondsPerQuarterNote, resolution,
 * timeSignature,patches);
 * try {
 * File myMidiFile = new File(fileName);
 * Sequence seq = MidiSystem.getSequence(myMidiFile);
 *
 * // and now read the file back out and see what we get.
 * PianoRoll pr = PianoRollViewParser.parse(seq);
 *
 * NotesInMidi[] notes = pr.getNotes();
 * for(int i=0;i &lt notes.length;i++){
 * System.out.println(notes[i].toString());
 * }
 *
 * // also get the program changes
 * ProgramChangesInMidi changesInMidi = ProgramChangeViewParser.parse(seq);
 * ProgramChangeInTrack[] changesInTrack =
 * changesInMidi.getProgramChanges();
 * for(int i=0;i &lt changesInTrack.length;i++){
 * System.out.println(changesInTrack[i].toString());
 * }
 *
 * } catch (SequenceDivisionTypeException ex) {
 * Logger.getLogger(SimpleWriteMidiExample.class.getName()).log(Level.SEVERE, null, ex);
 * }
 * }
 *
 * </pre></blockquote>
 *
 * @author Christine
 */
public class SimpleWriteMidiExample {

    /**
     * Shows how to use the {@link SimpleMidiWriter} to create a simple midi
     * file with three notes on track one.
     */
    public static void main(String[] args) throws InvalidMidiDataException,
            InvalidTempo, IOException, InvalidSignature {

        // define the onset of each note in beats
        double[] onset = {0, 2, 5};
        // make each note 2 beats long
        double[] duration = {2, 2, 2};
        // pitches C4 (middle C), D4, E4
        int[] pitch = {60, 62, 64};
        // put each note on a different channel
        int[] channel = {0, 1, 2};
        // use a variety of velocity (loudness) values
        int[] velocity = {40, 65, 90};
        // put all the notes on track 1
        int[] track = {1, 1, 1};
        //  channel  |   track   |   patch number:
        //     0           1               0  (acoustic grand piano)
        //     1           1               56 (trumpet)
        //     2           1               22 (harmonica)
        int[][] patches = {{0, 1, 0}, {1, 1, 56}, {2, 1, 22}};
        // pick half a second per quarter note
        int microsecondsPerQuarterNote = (int) (0.5 * 1000000.0);
        // pick a resolution of 100 ticks per beat.  This means that the
        // shortest note we can specify is 1/100th of a beat.
        int resolution = 100;
        // pick a time signature, 4/4 (common time) with 1 midi clock per
        // quarter note and 8 32nd notes per quarter note.
        int[] timeSignature = {4, 4, 1, 8};
        // pick some name for the resulting file.
        String fileName = "Temp.mid";

        // write the file
        SimpleMidiWriter.write(fileName, onset, duration, channel, pitch,
                velocity, track, microsecondsPerQuarterNote, resolution,
                timeSignature, patches);
        try {
            File myMidiFile = new File(fileName);
            Sequence seq = MidiSystem.getSequence(myMidiFile);

            // and now read the file back out and see what we get.
            PianoRoll pr = PianoRollViewParser.parse(seq);

            NotesInMidi[] notes = pr.getNotes();
            for (NotesInMidi note : notes) {
                System.out.println(note.toString());
            }

            // also get the program changes
            ProgramChangesInMidi changesInMidi = ProgramChangeViewParser.parse(seq);
            ProgramChangeInTrack[] changesInTrack =
                    changesInMidi.getProgramChanges();
            for (ProgramChangeInTrack programChangeInTrack : changesInTrack) {
                System.out.println(programChangeInTrack.toString());
            }

        } catch (SequenceDivisionTypeException ex) {
            Logger.getLogger(SimpleWriteMidiExample.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
