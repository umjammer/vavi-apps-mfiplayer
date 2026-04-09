/*
 * LyricsViewParser.java
 *
 * Created on Nov 12, 2007, 3:07:24 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.Lyric;


/**
 * Parses a {@link Sequence} for lyric commands, which it combines together into
 * an {@link LyricsInMidi}.
 *
 * @author Christine
 */
public class LyricsViewParser {

    /**
     * Parses a sequence and extracts the lyric events.  Note that the sequence
     * must have division type {@link javax.sound.midi.Sequence#PPQ pulses
     * (ticks) per quarter note}.
     *
     * @param seq The sequence you want to parse
     * @return the lyrics
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     */
    public static LyricsInMidi parse(Sequence seq) throws SequenceDivisionTypeException {
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new SequenceDivisionTypeException();
        }


        ArrayList<MidiCommandSorter.Info> commandList = MidiCommandSorter.sort(seq);
        ArrayList<LyricInTrackImp> allLyrics = new ArrayList<>();
        for (MidiCommandSorter.Info info : commandList) {
            if (info.getMidiCommand() instanceof Lyric lyric) {
                TrackMidi trackMidi = new TrackMidiImp(info.getTrack(),
                        info.getSeconds(), info.getTicks());
                LyricInTrackImp tli = new LyricInTrackImp(trackMidi, lyric);

                allLyrics.add(tli);
            }
        }

        ArrayList<LyricInTrack> tmp = new ArrayList<>(allLyrics);
        return new LyricsInMidi(tmp);

    }


    /**
     * Implementation of {@link LyricInTrack} for the LyricsViewParser.
     */
    private static class LyricInTrackImp implements LyricInTrack {

        private final TrackMidi trackMidi;
        private final Lyric lyric;

        /**
         * Constructor.
         */
        public LyricInTrackImp(TrackMidi trackMidi, Lyric lyric) {
            this.trackMidi = trackMidi;
            this.lyric = lyric;
        }


        @Override
        public long getTicks() {
            return trackMidi.getTicks();
        }

        @Override
        public double getSeconds() {
            return trackMidi.getSeconds();
        }

        @Override
        public int getTrackNumber() {
            return trackMidi.getTrackNumber();
        }

        @Override
        public byte[] getTextBytes() {
            return lyric.getTextBytes();
        }

        /**
         * Writes out the lyric as follows:
         * Lyric: "This is the lyric" , Time - ticks (seconds), Track Number - number
         */
        @Override
        public String toString() {

            String sb = "Lyric: \"" + lyric.getText() + "\"" +
                    " , Time - " + trackMidi.getTicks() + "(" +
                    trackMidi.getSeconds() + "), Track Number - " +
                    trackMidi.getTrackNumber();

            return sb;
        }

        @Override
        public String getText() {
            return lyric.getText();
        }

        @Override
        public int getLength() {
            return lyric.getLength();
        }

        @Override
        public byte[] getMessage() {
            return lyric.getMessage();
        }
    }
}
