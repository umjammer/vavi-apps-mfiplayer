/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.examples;

import java.io.File;
import java.util.ArrayList;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.read.MidiCommandSorter;
import edu.columbia.ee.csmit.midiKaraoke.read.MidiCommandSorter.Info;


/**
 * Shows how to use the {@link MidiCommandSorter} class.  This class takes a
 * {@link Sequence}, parses its commands into {@link MidiMessage} objects and
 * sorts the messages into time order.  It then calculates the time in seconds
 * for each command using the tick information and information from tempo
 * changes.
 *
 * <blockquote><pre>
 * public static void main(String[] args) {
 * if(args.length == 0){
 * System.out.println("Usage: MidiCommandSorter [midi file name]");
 * return;
 * }
 *
 *
 * try {
 *
 * File file = new File(args[0]);
 * Sequence mySeq = MidiSystem.getSequence(file);
 *
 * ArrayList&#60Info&gt commands = MidiCommandSorter.sort(mySeq);
 * Iterator&#60Info&gt it = commands.iterator();
 * // Note that each line here contains the midi command, the track
 * // number, the time of the command in seconds, and the time in
 * // ticks.  Also note that the midi commands appear in time-order,
 * // not file order.  The time in seconds has been calculated from
 * // the tempo changes in the file.
 * while(it.hasNext()){
 * System.out.println(it.next());
 * }
 *
 *
 * } catch (Exception e) {
 * System.out.println("Problem!");
 * e.printStackTrace();
 * System.out.println(e.toString());
 * }
 *
 *
 * }
 * </pre></blockquote>
 *
 * @author Christine
 */
public class MidiCommandSorterExample {

    /**
     * Shows how to use the {@link MidiCommandSorter} class to parse a
     * {@link Sequence}.  Prints out all of the midi commands it parses.
     *
     * @param args the command line arguments.  The first (and only) parameter
     *             is the name of a midi file.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: MidiCommandSorter [midi file name]");
            return;
        }


        try {

            File file = new File(args[0]);
            Sequence mySeq = MidiSystem.getSequence(file);

            ArrayList<Info> commands = MidiCommandSorter.sort(mySeq);
            // Note that each line here contains the midi command, the track
            // number, the time of the command in seconds, and the time in
            // ticks.  Also note that the midi commands appear in time-order,
            // not file order.  The time in seconds has been calculated from
            // the tempo changes in the file.
            for (Info command : commands) {
                System.out.println(command);
            }


        } catch (Exception e) {
            System.out.println("Problem!");
            e.printStackTrace();
            System.out.println(e);
        }


    }

}
