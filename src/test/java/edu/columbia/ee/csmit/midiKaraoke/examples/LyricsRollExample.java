/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.examples;

import java.io.File;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.read.LyricsInMidi;
import edu.columbia.ee.csmit.midiKaraoke.read.LyricsViewParser;


/**
 * Shows how to use the {@link LyricsViewParser}.
 *
 * <blockquote><pre>
 * public static void main(String[] args) {
 * if(args.length == 0){
 * System.out.println("Usage: LyricsRollExample [midi file name]");
 * return;
 * }
 *
 *
 * try {
 *
 * File file = new File(args[0]);
 * Sequence mySeq = MidiSystem.getSequence(file);
 *
 * LyricsInMidi lyricsRoll = LyricsViewParser.parse(mySeq);
 *
 * // get the track numbers with lyrics
 * int[] tracks = lyricsRoll.getTrackNumbers();
 *
 * // Separate out the lyrics by track...
 * for(int i=0;i &lt tracks.length;i++){
 * System.out.format("****** Track %d:\n", tracks[i]);
 * LyricsInMidi.Line[] lines = lyricsRoll.getLines(tracks[i]);
 * for(int j=0;j &lt lines.length;j++){
 * // Print out the time in seconds and the line
 * System.out.format("%f: %s\n",lines[j].getSeconds(),
 * lines[j].getLine());
 * }
 * System.out.println("");
 * }
 *
 * } catch (Exception e) {
 * System.out.println("Problem!");
 * e.printStackTrace();
 * System.out.println(e.toString());
 * }
 * }
 *
 * </blockquote></pre>
 *
 * @author Christine
 */
public class LyricsRollExample {

    /**
     * Shows how to use the {@link LyricsViewParser} to parse just the lyrics
     * in a {@link Sequence}.  Prints out the midi commands it reads.
     *
     * @param args the command line arguments.  The first (and only) argument
     *             should be the name of a midi file.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: LyricsRollExample [midi file name]");
            return;
        }

        try {
            File file = new File(args[0]);
            Sequence mySeq = MidiSystem.getSequence(file);

            LyricsInMidi lyricsRoll = LyricsViewParser.parse(mySeq);

            // get the track numbers with lyrics
            int[] tracks = lyricsRoll.getTrackNumbers();

            // Separate out the lyrics by track...
            for (int track : tracks) {
                System.out.format("****** Track %d:\n", track);
                LyricsInMidi.Line[] lines = lyricsRoll.getLines(track);
                for (LyricsInMidi.Line line : lines) {
                    // Print out the time in seconds and the line
                    System.out.format("%f: %s\n", line.getSeconds(),
                            line.getLine());
                }
                System.out.println();
            }

        } catch (Exception e) {
            System.out.println("Problem!");
            e.printStackTrace();
            System.out.println(e);
        }
    }
}
