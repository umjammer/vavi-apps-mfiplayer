/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.write;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.RawMidiMessageParser;


/**
 * This class writes a midi file with a single tempo.
 *
 * @author Christine
 */
public class SimpleMidiWriter {

    /**
     * Creates a simple midi file.  The parameters onset, duration, channel,
     * pitch, velocity, and track should all be arrays that are the same length as
     * the number of notes.  For example,
     * <pre>
     * {@code
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
     *        velocity, track, microsecondsPerQuarterNote, resolution,
     *        timeSignature,patches);
     * }
     * </pre>
     * would give you a midi file with three notes.
     *
     * @param fileName                   - name of the file to create
     * @param onset                      - array of note onsets in beats
     * @param duration                   - array of note durations in beats
     * @param channel                    - array of note channel numbers
     * @param pitch                      - array of note pitches
     * @param velocity                   - array of note velocity (volume) values
     * @param track                      - array of note track numbers
     * @param microsecondsPerQuarterNote - number of microseconds per quarter
     *                                   note (beat)
     * @param resolution                 - number of ticks per beat.  Indicates the level of
     *                                   quantization of the note durations.
     * @param timeSignature              - a 4-element array with the 4 parts of a midi time
     *                                   signature message.  timeSignature[0] = numerator of the time signature
     *                                   (i.e. 4 for 4/4 time), timeSignature[1] = the denominator of the time
     *                                   signature (i.e. 2 for 4/4 time, 3 for 6/8, etc.), timeSignature[3] =
     *                                   the number of midi clocks per quarter note, timeSignature[4] = the
     *                                   number of 32nd notes per quarter note.
     * @param patches                    - an Nx3 element array, where N is the number patches
     *                                   (instruments) you want to specify.  These patches will be set at the
     *                                   beginning of the midi file.  Each row should specify the channel,
     *                                   track, and patch number in that order.  If null, no instruments will
     *                                   be specified.
     * @throws MidiUnavailableException
     * @throws InvalidMidiDataException
     * @throws edu.columbia.ee.csmit.midiKaraoke.write.PianoRollWriter.InvalidTempo
     * @throws IOException
     */
    public static void write(String fileName, double[] onset, double[] duration,
                             int[] channel, int[] pitch, int[] velocity, int[] track,
                             int microsecondsPerQuarterNote,
                             int resolution, int[] timeSignature, int[][] patches)
            throws InvalidMidiDataException,
            InvalidTempo, IOException, InvalidSignature {

        // figure out how many tracks we need
        int max_track = 0;
        for (int j : track) {
            if (j > max_track) {
                max_track = j;
            }
        }

        // create a sequence with the correct number of tracks
        Sequence seq = new Sequence(Sequence.PPQ, resolution, max_track + 1);
        // get the tracks out
        Track[] tracks = seq.getTracks();

        // Create the messages that go in track zero.

        // Create the time signature message
        MetaMessage timeSignatureMessage =
                createTimeSignatureMessage(timeSignature);
        MidiEvent timeSignatureEvent = new MidiEvent(timeSignatureMessage, 0);
        tracks[0].add(timeSignatureEvent);

        // now create a tempo midi message
        MetaMessage tempoMessage =
                createTempoMessage(microsecondsPerQuarterNote);
        //System.out.println(toHex(tempoMessage.getMessage()));
        MidiEvent tempoEvent = new MidiEvent(tempoMessage, 0);
        tracks[0].add(tempoEvent);


        // now add the patch commands...
        if (patches != null) {
            for (int[] patch : patches) {
                int patch_channel = patch[0];
                int patch_track = patch[1];
                int patch_number = patch[2];

                // if we don't have any notes in this track, then we don't need
                // to set the instrument...
                if (patch_track <= max_track) {

                    ShortMessage msg = new ShortMessage();
                    // note that we don't need the second byte for this message...
                    msg.setMessage(ShortMessage.PROGRAM_CHANGE, patch_channel,
                            patch_number, 0);
                    MidiEvent event = new MidiEvent(msg, 0);

                    tracks[patch_track].add(event);
                }

            }
        }
        // and start adding the notes...
        ArrayList<MidiEventAndTrack> noteList =
                new ArrayList<>(onset.length);
        for (int i = 0; i < onset.length; i++) {
            long onsetInTicks = (long) (onset[i] * resolution);
            long durationInTicks = (long) (duration[i] * resolution);
            addNote(noteList, onsetInTicks, durationInTicks, channel[i], pitch[i],
                    velocity[i], track[i]);
        }

        // sort them according to their times...
        Collections.sort(noteList);

        // and add them to the track
        for (MidiEventAndTrack obj : noteList) {
            int track_num = obj.track;
            MidiEvent event = obj.event;
            tracks[track_num].add(event);

            //System.out.println("" + me.getTick() + ": " +
            //        toHex(me.getMessage().getMessage()));
        }

        // finally, write the sequency out to a file
        File file = new File(fileName);
        MidiSystem.write(seq, 1, file);
    }

    /**
     * Creates a simple midi file which does not specify patches (instruments).
     * The parameters onset, duration, channel,
     * pitch,velocity, and track should all be arrays that are the same length as
     * the number of notes.
     *
     * @param fileName                   - name of the file to create
     * @param onset                      - array of note onsets in beats
     * @param duration                   - array of note durations in beats
     * @param channel                    - array of note channel numbers
     * @param pitch                      - array of note pitches
     * @param velocity                   - array of note velocity (volume) values
     * @param track                      - array of note track numbers
     * @param microsecondsPerQuarterNote - number of microseconds per quarter
     *                                   note (beat)
     * @param resolution                 - number of ticks per beat.  Indicates the level of
     *                                   quantization of the note durations.
     * @param timeSignature              - a 4-element array with the 4 parts of a midi time
     *                                   signature message.  timeSignature[0] = numerator of the time signature
     *                                   (i.e. 4 for 4/4 time), timeSignature[1] = the denominator of the time
     *                                   signature (i.e. 2 for 4/4 time, 3 for 6/8, etc.), timeSignature[3] =
     *                                   the number of midi clocks per quarter note, timeSignature[4] = the
     *                                   number of 32nd notes per quarter note.
     * @throws MidiUnavailableException
     * @throws InvalidMidiDataException
     * @throws edu.columbia.ee.csmit.midiKaraoke.write.PianoRollWriter.InvalidTempo
     * @throws IOException
     */
    public static void write(String fileName, double[] onset, double[] duration,
                             int[] channel, int[] pitch, int[] velocity, int[] track,
                             int microsecondsPerQuarterNote,
                             int resolution, int[] timeSignature)
            throws InvalidMidiDataException,
            InvalidTempo, IOException, InvalidSignature {

        write(fileName, onset, duration, channel, pitch, velocity, track,
                microsecondsPerQuarterNote, resolution, timeSignature, null);
    }


    /**
     * Creates a simple midi file that does not specify instruments and place
     * all notes in track 0.  The parameters onset, duration, channel,
     * pitch, and velocity should all be arrays that are the same length as
     * the number of notes.
     *
     * @param fileName                   - name of the file to create
     * @param onset                      - array of note onsets in beats
     * @param duration                   - array of note durations in beats
     * @param channel                    - array of note channel numbers
     * @param pitch                      - array of note pitches
     * @param velocity                   - array of note velocity (volume) values
     * @param microsecondsPerQuarterNote - number of microseconds per quarter
     *                                   note (beat)
     * @param resolution                 - number of ticks per beat.  Indicates the level of
     *                                   quantization of the note durations.
     * @param timeSignature              - a 4-element array with the 4 parts of a midi time
     *                                   signature message.  timeSignature[0] = numerator of the time signature
     *                                   (i.e. 4 for 4/4 time), timeSignature[1] = the denominator of the time
     *                                   signature (i.e. 2 for 4/4 time, 3 for 6/8, etc.), timeSignature[3] =
     *                                   the number of midi clocks per quarter note, timeSignature[4] = the
     *                                   number of 32nd notes per quarter note.
     * @throws MidiUnavailableException
     * @throws InvalidMidiDataException
     * @throws edu.columbia.ee.csmit.midiKaraoke.write.PianoRollWriter.InvalidTempo
     * @throws IOException
     */
    public static void write(String fileName, double[] onset, double[] duration,
                             int[] channel, int[] pitch, int[] velocity,
                             int microsecondsPerQuarterNote,
                             int resolution, int[] timeSignature)
            throws InvalidMidiDataException,
            InvalidTempo, IOException, InvalidSignature {

        // put all the notes on track 0;
        int[] track = new int[onset.length];
        for (int i = 0; i < track.length; i++) {
            track[i] = 0;
        }

        write(fileName, onset, duration, channel, pitch, velocity, track,
                microsecondsPerQuarterNote, resolution, timeSignature,
                null);

    }

    private static MetaMessage createTempoMessage(
            int microsecondsPerQuarterNote) throws InvalidTempo, InvalidMidiDataException {
        // the data for this message is 3 bytes long
        int length = 3;
        byte[] data = new byte[length];

        if (microsecondsPerQuarterNote < 0 ||
                microsecondsPerQuarterNote > 0xFFFFFF) {
            throw new InvalidTempo();
        }

        data[0] = (byte) ((microsecondsPerQuarterNote >> 16) & 0xFF);
        data[1] = (byte) ((microsecondsPerQuarterNote >> 8) & 0xFF);
        data[2] = (byte) (microsecondsPerQuarterNote & 0xFF);

        // now create the new midi message
        MetaMessage ret = new MetaMessage();
        ret.setMessage(RawMidiMessageParser.SET_TEMPO, data, length);


        return ret;
    }

    private static MetaMessage createTimeSignatureMessage(int[] timeSignature)
            throws InvalidSignature, InvalidMidiDataException {
        int length = 4;
        if (timeSignature.length != length) {
            throw new InvalidSignature("Signature have 4 numbers.");
        }
        // copy to a byte array
        byte[] sig = new byte[length];
        for (int i = 0; i < length; i++) {
            sig[i] = (byte) timeSignature[i];
            // make sure that the number didn't get changed
            if ((int) sig[i] != timeSignature[i]) {
                throw new InvalidSignature("Signature value too large.");
            }
        }


        MetaMessage ret = new MetaMessage();
        ret.setMessage(RawMidiMessageParser.TIME_SIGNATURE, sig,
                length);

        return ret;
    }

    private static void addNote(ArrayList<MidiEventAndTrack> noteList,
                                long onset, long duration, int channel, int pitch,
                                int velocity, int track) throws InvalidMidiDataException {

        ShortMessage noteOn = new ShortMessage();
        noteOn.setMessage(ShortMessage.NOTE_ON, channel, pitch, velocity);
        MidiEvent onEvent = new MidiEvent(noteOn, onset);
        noteList.add(new MidiEventAndTrack(onEvent, track));

        ShortMessage noteOff = new ShortMessage();
        noteOff.setMessage(ShortMessage.NOTE_OFF, channel, pitch, velocity);
        MidiEvent offEvent = new MidiEvent(noteOff, onset + duration);
        noteList.add(new MidiEventAndTrack(offEvent, track));
    }

    private static class MidiEventAndTrack
            implements Comparable<MidiEventAndTrack> {

        public final MidiEvent event;
        public final int track;

        public MidiEventAndTrack(MidiEvent e, int track) {
            this.event = e;
            this.track = track;
        }

        @Override
        public int compareTo(MidiEventAndTrack me) {

            return Long.compare(event.getTick(), me.event.getTick());
        }
    }

    public static class InvalidTempo extends Exception {

        public InvalidTempo() {
            super("Tempo must be between 0x0 and 0xFFFFFF");
        }
    }

    public static class InvalidSignature extends Exception {

        public InvalidSignature(String str) {
            super(str);
        }
    }

    private static String toHex(byte[] b) {
        String ret = "";
        for (byte value : b) {
            ret = ret + "  " + toHex(value);
        }
        return ret;
    }

    private static String toHex(byte b) {
        int upper = (b & 0xF0) >> 4;
        int lower = b & 0x0F;

        return String.format("%1$X%2$X", upper, lower);
    }
}
