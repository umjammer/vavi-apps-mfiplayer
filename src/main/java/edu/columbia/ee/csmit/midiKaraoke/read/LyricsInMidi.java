/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.read;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;


/**
 * Stores all the lyrics associated with a particular midi file.
 *
 * @author Christine
 */
public class LyricsInMidi {

    // lyrics in time order
    private final ArrayList<LyricInTrack> lyrics;

    /**
     * Constructs a lyrics roll.  Basically, a piano roll is for piano notes.
     * This is the same idea, but for lyrics.
     *
     * @param lyrics the lyrics arranged in time order
     */
    public LyricsInMidi(ArrayList<LyricInTrack> lyrics) {
        this.lyrics = lyrics;
    }

    /**
     * Gets all the lyrics from all the tracks.
     *
     * @return lyrics and their associated times.
     */
    public LyricInTrack[] getLyrics() {
        LyricInTrack[] ret = new LyricInTrack[lyrics.size()];
        lyrics.toArray(ret);
        return ret;
    }

    /**
     * Finds all the tracks associated with lyrics.
     *
     * @return the tracks that have lyrics
     */
    public int[] getTrackNumbers() {
        Set<Integer> trackNumber = new TreeSet<>();
        for (LyricInTrack lyric : lyrics) {
            trackNumber.add(lyric.getTrackNumber());
        }

        int[] ret = new int[trackNumber.size()];
        Iterator<Integer> it = trackNumber.iterator();
        int i = 0;
        while (it.hasNext()) {
            ret[i] = it.next();
            i = i + 1;
        }
        return ret;

    }

    /**
     * Get the lyrics from a particular track
     *
     * @param trackNumber - the track number of the lyrics
     * @return the lyrics and their associated times.
     */
    public LyricInTrack[] getLyrics(int trackNumber) {
        ArrayList<LyricInTrack> tmp = new ArrayList<>();
        for (LyricInTrack lyric : lyrics) {
            if (lyric.getTrackNumber() == trackNumber) {
                tmp.add(lyric);
            }
        }
        LyricInTrack[] ret = new LyricInTrack[lyrics.size()];
        tmp.toArray(ret);
        return ret;
    }

    /**
     * Gets the lines of lyrics.  Adds a blank line when it sees a 0xD
     * (carriage return) or 0xC (new page).
     *
     * @param trackNumber - track number of lyrics
     * @return lines
     */
    public Line[] getLines(int trackNumber) {
        ArrayList<Line> lines = new ArrayList<>();
        String line = "";
        double seconds = 0;
        long ticks = 0;
        boolean inline = false;
        for (LyricInTrack lyricInTrack : lyrics) {
            if (lyricInTrack.getTrackNumber() == trackNumber) {
                LyricInTrack lyric = lyricInTrack;
                byte[] bytes = lyric.getTextBytes();

                if (bytes.length == 1 && bytes[0] == 0xd) {
                    // move on to a new line
                    lines.add(new Line(line, seconds, ticks));
                    inline = false;
                } else if (bytes.length == 1 && bytes[0] == 0xa) {
                    // a new verse, so add a blank line
                    lines.add(new Line("", lyric.getSeconds(),
                            lyric.getTicks()));
                    inline = false;
                } else if (inline) {
                    // add this lyric to the current line
                    line = line + lyric.getText();
                } else {
                    // start of a new line
                    line = lyric.getText();
                    seconds = lyric.getSeconds();
                    ticks = lyric.getTicks();
                    inline = true;
                }
            }
        }
        Line[] ret = new Line[lines.size()];
        lines.toArray(ret);
        return ret;
    }

    /**
     * Stores a line of lyrics.
     */
    public static class Line {

        private final String line;
        private final double seconds;
        private final long ticks;

        /**
         * Constructor
         *
         * @param line    - line of lyrics
         * @param seconds - time of start of the line of lyrics in seconds
         * @param ticks   - time of the start of the line of lyrics in ticks
         */
        public Line(String line, double seconds, long ticks) {
            this.line = line;
            this.seconds = seconds;
            this.ticks = ticks;
        }

        /**
         * The line of lyrics.
         *
         * @return the line
         */
        public String getLine() {
            return line;
        }

        /**
         * The time in seconds of the start of the line of lyrics.
         *
         * @return time in seconds
         */
        public double getSeconds() {
            return seconds;
        }

        /**
         * The time of the start of line of lyrics in ticks.
         *
         * @return time in ticks
         */
        public long getTicks() {
            return ticks;
        }

    }
}
