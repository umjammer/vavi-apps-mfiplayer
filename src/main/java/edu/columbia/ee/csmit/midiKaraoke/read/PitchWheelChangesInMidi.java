/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;


/**
 * Stores all the pitch wheel roll commands from a midi file along with their
 * times.
 *
 * @author Christine
 */
public class PitchWheelChangesInMidi {

    private final ArrayList<PitchWheelChangeInTrack> events;

    public PitchWheelChangesInMidi(ArrayList<PitchWheelChangeInTrack> events) {
        this.events = events;
    }

    /**
     * Returns the pitch wheel changes for the file in time order.
     *
     * @return the pitch wheel changes
     */
    public PitchWheelChangeInTrack[] getPitchWheelChanges() {
        PitchWheelChangeInTrack[] ret = new PitchWheelChangeInTrack[events.size()];
        events.toArray(ret);
        return ret;
    }

    /**
     * Returns the pitch wheel changes as a 2-d array.  Each row of the
     * array represents a single pitch wheel change command.  The columns
     * are: <br />
     * 0 - the value of the wheel <br />
     * 1 - the time of the command in ticks <br />
     * 2 - the time of the command in seconds <br />
     * 3 - the trackNumber <br />
     * 4 - the channel <br />
     *
     * @return the pitch wheel changes
     */
    public double[][] getPitchWheelChangesDoubles() {

        double[][] ret = new double[events.size()][5];
        for (int i = 0; i < events.size(); i++) {
            ret[i][0] = events.get(i).getValue();
            ret[i][1] = events.get(i).getTicks();
            ret[i][2] = events.get(i).getSeconds();
            ret[i][3] = events.get(i).getTrackNumber();
            ret[i][4] = events.get(i).getChannel();
        }
        return ret;
    }


}
