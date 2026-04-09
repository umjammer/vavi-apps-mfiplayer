/*
 * MidiMessageSorter.java
 *
 * Created on Nov 26, 2007, 4:30:46 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.MidiCommand;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.RawMidiMessageParser;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.SetTempo;


/**
 * Takes a {@link Sequence}, parses the midi commands, sorts them in time order,
 * and calculates the time (in seconds) at which each occurs.
 *
 * @author Christine
 */
public class MidiCommandSorter {

    /**
     * This function takes a {@link Sequence} seq, parses its midi commands,
     * sorts them in time order, and calculates the time (in seconds) at which
     * each occurs.  The time in seconds is calculated by combining the tick
     * information for each command with the tempo change commands.
     *
     * @param seq
     * @return a time-sorted list of {@link Info} objects, which each contain an
     * {@link MidiCommand}, the track for this command, the time in ticks for
     * this command, and the time in seconds
     * @throws SequenceDivisionTypeException
     */
    public static ArrayList<Info> sort(Sequence seq) throws SequenceDivisionTypeException {
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new SequenceDivisionTypeException();
        }

        // find out what the resolution is
        int ticksPerQuarterNote = seq.getResolution();

        // initialize a time advancer to keep track of the current time
        TimeAdvancer timeAdvancer = new TimeAdvancer(ticksPerQuarterNote);

        // create a parser for the midi messages
        RawMidiMessageParser messageParser = new RawMidiMessageParser();


        // get the midi events, sorted by tick time
        ArrayList<MidiEventsTimeSorter.EventAndTrack> eventList =
                MidiEventsTimeSorter.sort(seq);

        // set up a new list for the sorted midi commands
        ArrayList<Info> midiCommandList = new ArrayList<>();

        for (MidiEventsTimeSorter.EventAndTrack eventAndTrack : eventList) {
            MidiEvent midiEvent = eventAndTrack.getMidiEvent();
            int trackNumber = eventAndTrack.getTrackNumber();

            // advance time with this new midi event's tick value
            timeAdvancer.advanceTime(midiEvent.getTick());

            // parse the midi message
            MidiCommand midiCommand = messageParser.parse(midiEvent.getMessage());

            // change the tempo, if necessary
            if (midiCommand instanceof SetTempo st) {
                timeAdvancer.setMicrosecondsPerQuarterNote(
                        st.getMicrosecondsPerQuarterNote());
            }

            Info info = new Info(midiCommand, trackNumber,
                    timeAdvancer.getCurrTicks(), timeAdvancer.getCurrTime());

            midiCommandList.add(info);
        }

        return midiCommandList;
    }

    /**
     * Stores a {@link MidiCommand} and all the useful information about it in
     * the midi stream, namely the track number, time in ticks, and time in
     * seconds.
     */
    public static class Info {

        private final MidiCommand mc;
        private final int track;
        private final long ticks;
        private final double seconds;

        /**
         * Constructor.
         *
         * @param mc      the midi command
         * @param track   the track number for mc
         * @param ticks   the time in ticks for mc
         * @param seconds the time in seconds for mc
         */
        public Info(MidiCommand mc, int track, long ticks, double seconds) {
            this.mc = mc;
            this.track = track;
            this.ticks = ticks;
            this.seconds = seconds;
        }

        public MidiCommand getMidiCommand() {
            return mc;
        }

        public int getTrack() {
            return track;
        }

        public long getTicks() {
            return ticks;
        }

        public double getSeconds() {
            return seconds;
        }

        @Override
        public String toString() {
            return "[" + mc + "]" + " track " + track + ", " + seconds + " (" + ticks + ")";
        }
    }

    /**
     * Keeps track of the time as tempo changes occur.
     */
    private static class TimeAdvancer {

        private int ticksPerQuarterNote = 0;
        // initialize the tempo information to something
        double currTime = 0;
        long currTicks = 0;
        double seconds_per_quarter_note = 0;

        public TimeAdvancer(int ticksPerQuarterNote) {
            this.ticksPerQuarterNote = ticksPerQuarterNote;
        }

        public double getCurrTime() {
            return currTime;
        }

        public long getCurrTicks() {
            return currTicks;
        }

        public void setMicrosecondsPerQuarterNote(int microseconds_per_qn) {
            seconds_per_quarter_note = microseconds_per_qn / 1e6;
        }

        public void advanceTime(long ticks) {
            if (ticks != currTicks) {
                // calculate where we are now compared to the last time we saw
                long diff = ticks - currTicks;

                // figure out how many quarter notes we have increased
                double quarter_notes = diff / ((double) ticksPerQuarterNote);

                // figure out how many milliseconds this is
                double seconds = quarter_notes * seconds_per_quarter_note;

                // update the current time
                currTime = currTime + seconds;

                // update the current ticks
                currTicks = ticks;
            }
        }
    }

}