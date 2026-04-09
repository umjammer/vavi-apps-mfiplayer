/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.ProgramChange;


/**
 *
 * @author Christine
 */
public class ProgramChangeViewParser {

    /**
     * Parses a sequence and extracts the lyric events.  Note that the sequence
     * must have division type {@link javax.sound.midi.Sequence#PPQ pulses
     * (ticks) per quarter note}.
     *
     * @param seq The sequence you want to parse
     * @return the lyrics
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     */
    public static ProgramChangesInMidi parse(Sequence seq) throws
            SequenceDivisionTypeException {
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new SequenceDivisionTypeException();
        }

        ArrayList<MidiCommandSorter.Info> commandList =
                MidiCommandSorter.sort(seq);
        ArrayList<ProgramChangeInTrack> allPitchWheels =
                new ArrayList<>();
        for (MidiCommandSorter.Info info : commandList) {
            if (info.getMidiCommand() instanceof ProgramChange change) {
                TrackMidi trackMidi = new TrackMidiImp(info.getTrack(),
                        info.getSeconds(), info.getTicks());
                ProgramChangeInTrackImp pcit =
                        new ProgramChangeInTrackImp(trackMidi, change);

                allPitchWheels.add(pcit);
            }
        }
        return new ProgramChangesInMidi(allPitchWheels);
    }

    private static class ProgramChangeInTrackImp
            implements ProgramChangeInTrack {

        private final TrackMidi trackMidi;
        private final ProgramChange programChange;

        public ProgramChangeInTrackImp(TrackMidi trackMidi,
                                       ProgramChange programChange) {
            this.trackMidi = trackMidi;
            this.programChange = programChange;
        }

        @Override
        public int getProgramNumber() {
            return programChange.getProgramNumber();
        }

        @Override
        public int getLength() {
            return programChange.getLength();
        }

        @Override
        public byte[] getMessage() {
            return programChange.getMessage();
        }

        @Override
        public int getTrackNumber() {
            return trackMidi.getTrackNumber();
        }

        @Override
        public double getSeconds() {
            return trackMidi.getSeconds();
        }

        @Override
        public long getTicks() {
            return trackMidi.getTicks();
        }

        @Override
        public int getChannel() {
            return programChange.getChannel();
        }

        @Override
        public String toString() {
            String sb = "Program Change: " + programChange.getProgramNumber() +
                    " , Time - " + trackMidi.getTicks() + "(" +
                    trackMidi.getSeconds() + "), Track Number - " +
                    trackMidi.getTrackNumber() + " , Channel - " +
                    programChange.getChannel();
            return sb;
        }
    }
}
