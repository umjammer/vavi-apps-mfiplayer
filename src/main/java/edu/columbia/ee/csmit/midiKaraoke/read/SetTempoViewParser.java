/*
 * TempoViewParser.java
 *
 * Created on Nov 25, 2007, 4:49:15 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.sound.midi.Sequence;

import edu.columbia.ee.csmit.midiKaraoke.midiMessage.SetTempo;


/**
 * Used to parse a {@link Sequence} for tempo commands.
 *
 * @author Christine
 */
public class SetTempoViewParser {

    /**
     * Parses a sequence and extracts the tempo events.  Note that the sequence
     * must have division type {@link javax.sound.midi.Sequence#PPQ pulses
     * (ticks) per quarter note}.
     *
     * @param seq The sequence you want to parse
     * @return the tempo changes
     * @throws edu.columbia.ee.csmit.midiKaraoke.SequenceDivisionTypeException
     */
    public static SetTemposInMidi parse(Sequence seq) throws SequenceDivisionTypeException {

        ArrayList<MidiCommandSorter.Info> commandList =
                MidiCommandSorter.sort(seq);

        LinkedList<SetTempoInTrackImp> tempos = new LinkedList<>();
        for (MidiCommandSorter.Info info : commandList) {
            if (info.getMidiCommand() instanceof SetTempo st) {
                TrackMidi trackMidi = new TrackMidiImp(info.getTrack(),
                        info.getSeconds(), info.getTicks());
                SetTempoInTrackImp tti = new SetTempoInTrackImp(
                        trackMidi, st);

                tempos.add(tti);
            }
        }

        return new SetTemposInMidiImp(tempos);
    }

    private static class SetTemposInMidiImp implements SetTemposInMidi {

        private final SetTempoInTrack[] tempos;

        public SetTemposInMidiImp(List<SetTempoInTrackImp> listTempos) {
            tempos = new SetTempoInTrack[listTempos.size()];
            listTempos.toArray(tempos);
        }

        @Override
        public SetTempoInTrack[] getTempos() {
            return tempos;
        }

        @Override
        public double[][] getTemposDoubles() {
            double[][] ret = new double[tempos.length][4];

            for (int i = 0; i < tempos.length; i++) {
                SetTempoInTrack tt = tempos[i];
                ret[i][0] = tt.getMicrosecondsPerQuarterNote();
                ret[i][1] = tt.getTicks();
                ret[i][2] = tt.getSeconds();
                ret[i][3] = tt.getTrackNumber();
            }

            return ret;
        }

    }

    private static class SetTempoInTrackImp implements SetTempoInTrack {

        final TrackMidi trackMidi;
        final SetTempo setTempo;

        public SetTempoInTrackImp(TrackMidi trackMidi, SetTempo setTempo) {
            this.trackMidi = trackMidi;
            this.setTempo = setTempo;
        }

        @Override
        public int getMicrosecondsPerQuarterNote() {
            return setTempo.getMicrosecondsPerQuarterNote();
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

            String sb = "Tempo: " + setTempo.getMicrosecondsPerQuarterNote() +
                    " , Time - " + trackMidi.getTicks() +
                    "(" + trackMidi.getSeconds() + "), Track Number - " +
                    trackMidi.getTrackNumber();

            return sb;
        }

        @Override
        public int getLength() {
            return setTempo.getLength();
        }

        @Override
        public byte[] getMessage() {
            return setTempo.getMessage();
        }

    }
}
