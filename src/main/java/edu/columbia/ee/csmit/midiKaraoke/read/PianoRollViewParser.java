/*
 * PianoRollViewParser.java
 *
 * Created on Nov 8, 2007, 11:16:24 AM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.MidiCommand;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.NoteOff;
import edu.columbia.ee.csmit.midiKaraoke.midiMessage.NoteOn;


/**
 * Parses a {@link Sequence} or midi file into a piano roll.
 *
 * @author Christine
 */
public class PianoRollViewParser {

    private static final int OKAY = 0;
    /**
     * Indicates that a note off command was received for a note that was never
     * turned on.
     */
    private static final int UNMATCHED_NOTE_OFF = 1;
    /**
     * Indicates that a note off command was missing. Can occur if a note on
     * command appears twice for a particular pitch on a particular track and no
     * note-off command appears in the middle.
     */
    private static final int NOTE_OFF_MISSING = 2;

    /**
     * Parses a sequence into a piano roll. Note that the sequence must have
     * division type {@link javax.sound.midi.Sequence#PPQ pulses (ticks) per
     * quarter note}.
     *
     * @param fileName name of the midi file
     * @return the piano roll
     * @throws javax.sound.midi.InvalidMidiDataException
     * @throws java.io.IOException
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     * @throws edu.columbia.ee.csmit.midiKaraoke.PianoRollViewParser.UnfinishedNotesException
     */

    public static PianoRoll parse(String fileName)
            throws InvalidMidiDataException, IOException,
            SequenceDivisionTypeException, UnfinishedNotesException {
        return parse(fileName, true);

    }

    /**
     * Parses a sequence into a piano roll. Note that the sequence must have
     * division type {@link javax.sound.midi.Sequence#PPQ pulses (ticks) per
     * quarter note}.
     *
     * @param fileName name of the midi file
     * @param verbose  if true, will print out warning messages
     * @return the piano roll
     * @throws javax.sound.midi.InvalidMidiDataException
     * @throws java.io.IOException
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     * @throws edu.columbia.ee.csmit.midiKaraoke.PianoRollViewParser.UnfinishedNotesException
     */
    public static PianoRoll parse(String fileName, boolean verbose)
            throws InvalidMidiDataException, IOException,
            SequenceDivisionTypeException, UnfinishedNotesException {

        File myMidiFile = new File(fileName);
        Sequence seq = MidiSystem.getSequence(myMidiFile);

        return parse(seq, verbose);
    }

    /**
     * Parses a sequence into a piano roll. Note that the sequence must have
     * division type {@link javax.sound.midi.Sequence#PPQ pulses (ticks) per
     * quarter note}.
     *
     * @param seq the sequence to parse
     * @return a piano roll
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     * @throws edu.columbia.ee.csmit.midiKaraoke.PianoRollViewParser.UnfinishedNotesException
     */
    public static PianoRoll parse(Sequence seq)
            throws SequenceDivisionTypeException {
        return parse(seq, true);
    }

    /**
     * Parses a sequence into a piano roll. Note that the sequence must have
     * division type {@link javax.sound.midi.Sequence#PPQ pulses (ticks) per
     * quarter note}.
     *
     * @param seq     the sequence to parse
     * @param verbose if true, will print out warning messages
     * @return a piano roll
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     * @throws edu.columbia.ee.csmit.midiKaraoke.PianoRollViewParser.UnfinishedNotesException
     */
    public static PianoRoll parse(Sequence seq, boolean verbose)
            throws SequenceDivisionTypeException {

        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new SequenceDivisionTypeException();
        }

        // find out what the resolution is
        int ticksPerQuarterNote = seq.getResolution();

        // sort all the messages in time order
        ArrayList<MidiCommandSorter.Info> commandList = MidiCommandSorter
                .sort(seq);

        // create a map to hold the finished notes - in other words the notes
        // whose duration is known
        TreeMap<Long, LinkedList<NotesInMidi>> finishedNotes = new TreeMap<>();

        // create a list that holds the unfinished notes - those whose end
        // we haven't seen yet.
        List<NoteImp> unfinishedNotes = new LinkedList<>();

        for (MidiCommandSorter.Info info : commandList) {
            MidiCommand midiCommand = info.getMidiCommand();

            int ret = 0;
            // see what sort of event we have
            if (midiCommand instanceof NoteOn noteOn) {
                ret = processNote(true, noteOn.getNoteNumber(),
                        noteOn.getVelocity(), noteOn.getChannel(),
                        info.getTrack(), info.getTicks(), info.getSeconds(),
                        unfinishedNotes, finishedNotes);

            } else if (midiCommand instanceof NoteOff noteOff) {
                ret = processNote(false, noteOff.getNoteNumber(),
                        noteOff.getVelocity(), noteOff.getChannel(),
                        info.getTrack(), info.getTicks(), info.getSeconds(),
                        unfinishedNotes, finishedNotes);

            }

            if (ret != OKAY && verbose) {
                switch (ret) {
                    case UNMATCHED_NOTE_OFF:
                        System.out.println("Warning - unmatched note off command: "
                                + info);
                        break;
                    default:
                        System.out
                                .println("Warning - note off expected before new note on:"
                                        + info);
                        break;
                }
            }

        }

        // okay - so all the notes should be finished now
        if (!unfinishedNotes.isEmpty() && verbose) {
            System.out.println("Warning - there are " + unfinishedNotes.size()
                    + " unfinished notes.");
        }

        return new PianoRollImp(finishedNotes);

    }

    private static int processNote(boolean noteOn, int note, int velocity,
                                   int channel, int track, long tick, double seconds,
                                   List<NoteImp> unfinishedNotes,
                                   Map<Long, LinkedList<NotesInMidi>> finishedNotes) {

        int ret = OKAY;

        // First of all, see if we have currently unfinished notes with this
        // pitch. If we do, remove it. Even if this is a note on command,
        // we want to end the previous note on this pitch.
        Iterator<NoteImp> it = unfinishedNotes.iterator();
        NoteImp foundNote = null;
        while (it.hasNext() && foundNote == null) {
            NoteImp nextNote = it.next();

            // see if this is a note we want
            if (nextNote.getNote() == note && nextNote.getChannel() == channel
                    && nextNote.getTrackNumber() == track) {
                foundNote = nextNote;

            }

            // remove the note if we have found it and add it to the list of
            // finished notes
            if (foundNote != null) {
                it.remove();

                // The note ends now
                foundNote.setEnd(tick, seconds);

                // add it to our map
                LinkedList<NotesInMidi> ls = finishedNotes.computeIfAbsent(foundNote
                        .getStartTick(), k -> new LinkedList<>());
                ls.add(foundNote);
            }

        }

        // if this command was supposed to turn the note off, but no note was
        // found, make sure we return an error

        if ((!noteOn || (noteOn && velocity == 0)) && foundNote == null) {
            ret = UNMATCHED_NOTE_OFF;
        }

        // check to see if this was really a note on command
        if (noteOn && velocity != 0) {
            // we have a new note.
            NoteImp newNote = new NoteImp(note, velocity, tick, seconds, track,
                    channel);
            unfinishedNotes.add(newNote);

            // if this was a note on and we had to turn off another note first,
            // flag an error
            if (foundNote != null) {
                ret = NOTE_OFF_MISSING;
            }

        }

        return ret;

    }

    // private static void processNoteOff(NoteOff noteOff, List<NoteImp>
    // unfinishedNotes,
    // Map<Long,LinkedList<TimedNote>> finishedNotes, long currTicks,
    // double currTime, int trackNumber) {
    // // we have the end of a note. So we first have to find
    // // the beginning
    // Iterator<NoteImp> it = unfinishedNotes.iterator();
    // boolean done = false;
    // while (it.hasNext() && !done) {
    // NoteImp note = it.next();
    //
    // if (note.getNote() == noteOff.getNoteNumber()
    // && note.getTrackNumber() == trackNumber) {
    // // we found the correct note
    // done = true;
    // // update the end time for this note
    // note.setEnd(currTicks, currTime);
    //
    // // remove this note from the 'unfinished' list
    // it.remove();
    //
    // // add it to our map
    // LinkedList<TimedNote> ls = finishedNotes.get(note.getStartTick());
    // if (ls == null){
    // ls = new LinkedList<TimedNote>();
    // finishedNotes.put(note.getStartTick(),ls);
    // }
    // ls.add(note);
    // }
    // }
    // }
    //
    // private static void processNoteOn(NoteOn noteOn, List<NoteImp>
    // unfinishedNotes,
    // Map<Long, LinkedList<TimedNote>> finishedNotes, long currTicks, double
    // currTime,
    // int trackNumber) {
    //
    // if (noteOn.getVelocity() == 0) {
    // // we have the end of a note. So we first have to find
    // // the beginning
    // Iterator<NoteImp> it = unfinishedNotes.iterator();
    // boolean done = false;
    // while (it.hasNext() && !done) {
    // NoteImp note = it.next();
    //
    // if (note.getNote() == noteOn.getNoteNumber()
    // && note.getTrackNumber() == trackNumber) {
    // // we found the correct note
    // done = true;
    // // update the end time for this note
    // note.setEnd(currTicks, currTime);
    //
    // // remove this note from the 'unfinished' list
    // it.remove();
    //
    // // add it to our map
    // LinkedList<TimedNote> ls = finishedNotes.get(note.getStartTick());
    // if (ls == null) {
    // ls = new LinkedList<TimedNote>();
    // finishedNotes.put(note.getStartTick(), ls);
    // }
    // ls.add(note);
    //
    // }
    // }
    // } else {
    // // we have a new note.
    // NoteImp note = new NoteImp(noteOn.getNoteNumber(),
    // noteOn.getVelocity(), currTicks, currTime, trackNumber,
    // noteOn.getChannel());
    //
    // unfinishedNotes.add(note);
    // }
    //
    // }

    public static class UnfinishedNotesException extends Exception {

        public UnfinishedNotesException(int numUnfinished) {
            super("There are " + numUnfinished + " notes in the midi sequence.");
        }
    }

    private static class PianoRollImp implements PianoRoll {

        NotesInMidi[] notes = null;

        public PianoRollImp(Map<Long, LinkedList<NotesInMidi>> finishedNotes) {
            // first of all get everything onto a single list
            List<NotesInMidi> listNotes = new LinkedList<>();

            for (Long aLong : finishedNotes.keySet()) {
                LinkedList<NotesInMidi> noteList = finishedNotes.get(aLong);

                for (NotesInMidi notesInMidi : noteList) {
                    listNotes.add(notesInMidi);
                }

            }

            // now that we have the list - put everything into the notes array
            notes = new NotesInMidi[listNotes.size()];
            listNotes.toArray(notes);

        }

        @Override
        public NotesInMidi[] getNotes() {
            return notes;
        }

        @Override
        public double[][] getNotesDoubles() {
            double[][] ret = new double[notes.length][8];

            for (int i = 0; i < notes.length; i++) {
                ret[i][0] = notes[i].getNote();
                ret[i][1] = notes[i].getVelocity();
                ret[i][2] = notes[i].getChannel();
                ret[i][3] = notes[i].getStartTick();
                ret[i][4] = notes[i].getDurationTick();
                ret[i][5] = notes[i].getStartSeconds();
                ret[i][6] = notes[i].getDurationSeconds();
                ret[i][7] = notes[i].getTrackNumber();
            }

            return ret;

        }

    }

    private static class TrackInfoImp implements TrackInfo {

        private String name;
        private String instrumentName;
        private int number;

        public TrackInfoImp() {
            name = "";
            instrumentName = "";
            number = -1;
        }

        @Override
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getInstrumentName() {
            return instrumentName;
        }

        public void setInstrumentName(String instrumentName) {
            this.instrumentName = instrumentName;
        }

        @Override
        public int getNumber() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }

    }

    private static class NoteImp implements NotesInMidi {

        private final int note;
        private final int velocity;
        private final long startTick;
        private long durationTick;
        private final double startSeconds;
        private double durationSeconds;
        private final int track;
        private final int channel;

        public NoteImp(int note, int velocity, long startTick,
                       double startSeconds, int track, int channel) {
            this.note = note;
            this.velocity = velocity;
            this.startTick = startTick;
            this.durationTick = 0;
            this.startSeconds = startSeconds;
            this.durationSeconds = 0;
            this.track = track;
            this.channel = channel;
        }

        public void setEnd(long endTick, double endSeconds) {
            this.durationTick = endTick - startTick;
            this.durationSeconds = endSeconds - startSeconds;
        }

        @Override
        public int getNote() {
            return note;
        }

        @Override
        public int getVelocity() {
            return velocity;
        }

        @Override
        public long getStartTick() {
            return startTick;
        }

        @Override
        public long getDurationTick() {
            return durationTick;
        }

        @Override
        public double getStartSeconds() {
            return startSeconds;
        }

        @Override
        public double getDurationSeconds() {
            return durationSeconds;
        }

        @Override
        public int getTrackNumber() {
            return track;
        }

        @Override
        public int getChannel() {
            return channel;
        }

        @Override
        public String toString() {

            StringBuilder noteStr = new StringBuilder();

            // get the note name
            switch (note % 12) {
                case 0:
                    noteStr.append("C/B#");
                    break;
                case 1:
                    noteStr.append("C#/Db");
                    break;
                case 2:
                    noteStr.append("D");
                    break;
                case 3:
                    noteStr.append("D#/Eb");
                    break;
                case 4:
                    noteStr.append("E/Fb");
                    break;
                case 5:
                    noteStr.append("F/E#");
                    break;
                case 6:
                    noteStr.append("F#/Gb");
                    break;
                case 7:
                    noteStr.append("G");
                    break;
                case 8:
                    noteStr.append("G#/Ab");
                    break;
                case 9:
                    noteStr.append("A");
                    break;
                case 10:
                    noteStr.append("A#/Bb");
                    break;
                default:
                    noteStr.append("B/Cb");
            }

            // get the octave
            noteStr.append(note / 12);

            String sb = noteStr + " (" + note + ", vel " + velocity
                    + "): " +
                    "Channel - " + channel +
                    ", Start - " + startTick + "(" + startSeconds + ")" +
                    ", Duration - " + durationTick + "(" + durationSeconds
                    + ")" +
                    ", Track Number - " + track;

            return sb;
        }

    }
}
