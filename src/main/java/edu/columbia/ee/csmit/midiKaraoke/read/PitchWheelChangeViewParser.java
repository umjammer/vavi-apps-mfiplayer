/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.PitchWheelChange;


/**
 *
 * @author Christine
 */
public class PitchWheelChangeViewParser {

    /**
     * Parses a sequence and extracts the lyric events.  Note that the sequence
     * must have division type {@link javax.sound.midi.Sequence#PPQ pulses
     * (ticks) per quarter note}.
     *
     * @param seq The sequence you want to parse
     * @return the lyrics
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     */
    public static PitchWheelChangesInMidi parse(Sequence seq) throws
            SequenceDivisionTypeException {
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new SequenceDivisionTypeException();
        }

        ArrayList<MidiCommandSorter.Info> commandList =
                MidiCommandSorter.sort(seq);
        ArrayList<PitchWheelChangeInTrack> allPitchWheels =
                new ArrayList<>();
        for (MidiCommandSorter.Info info : commandList) {
            if (info.getMidiCommand() instanceof PitchWheelChange pitchWheel) {
                TrackMidi trackMidi = new TrackMidiImp(info.getTrack(),
                        info.getSeconds(), info.getTicks());
                PitchWheelChangeInTrackImp tpwi =
                        new PitchWheelChangeInTrackImp(trackMidi, pitchWheel);

                allPitchWheels.add(tpwi);
            }
        }
        return new PitchWheelChangesInMidi(allPitchWheels);
    }

    private static class PitchWheelChangeInTrackImp
            implements PitchWheelChangeInTrack {

        private final TrackMidi trackMidi;
        private final PitchWheelChange pitchWheel;

        public PitchWheelChangeInTrackImp(TrackMidi trackMidi,
                                          PitchWheelChange pitchWheelChange) {
            this.trackMidi = trackMidi;
            this.pitchWheel = pitchWheelChange;
        }

        @Override
        public int getValue() {
            return pitchWheel.getValue();
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
        public int getTrackNumber() {
            return trackMidi.getTrackNumber();
        }

        @Override
        public String toString() {
            String sb = "Pitch Wheel Change: " + pitchWheel.getValue() +
                    " , Time - " + trackMidi.getTicks() + "(" +
                    trackMidi.getSeconds() + "), Track Number - " +
                    trackMidi.getTrackNumber() + " , Channel - " +
                    pitchWheel.getChannel();
            return sb;
        }

        @Override
        public int getChannel() {
            return pitchWheel.getChannel();
        }

        @Override
        public int getLength() {
            return pitchWheel.getLength();
        }

        @Override
        public byte[] getMessage() {
            return pitchWheel.getMessage();
        }
    }
}
