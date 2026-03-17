/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;


/**
 * Stores all the program change commands for a midi file.
 *
 * @author Christine
 */
public class ProgramChangesInMidi {

    private final ArrayList<ProgramChangeInTrack> changes;

    public ProgramChangesInMidi(ArrayList<ProgramChangeInTrack> changes) {
        this.changes = changes;
    }

    /**
     * Returns the program change commands for this midi file.
     *
     * @return the program changes
     */
    public ProgramChangeInTrack[] getProgramChanges() {
        ProgramChangeInTrack[] ret = new ProgramChangeInTrack[changes.size()];
        changes.toArray(ret);
        return ret;
    }

    /**
     * Returns the program changes as a 2-d array.  Each row of the
     * array represents a single program change command.  The columns
     * are: <br />
     * 0 - the patch number <br />
     * 1 - the time of the command in ticks <br />
     * 2 - the time of the command in seconds <br />
     * 3 - the trackNumber <br />
     * 4 - the channel <br />
     *
     * @return the pitch wheel changes
     */
    public double[][] getProgramChangesDoubles() {

        double[][] ret = new double[changes.size()][5];
        for (int i = 0; i < changes.size(); i++) {
            ret[i][0] = changes.get(i).getProgramNumber();
            ret[i][1] = changes.get(i).getTicks();
            ret[i][2] = changes.get(i).getSeconds();
            ret[i][3] = changes.get(i).getTrackNumber();
            ret[i][4] = changes.get(i).getChannel();
        }
        return ret;
    }

}
