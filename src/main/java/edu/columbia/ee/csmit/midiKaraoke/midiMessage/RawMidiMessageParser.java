/*
 * MidiMessageParser.java
 *
 * Created on Nov 1, 2007, 6:30:19 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;


/**
 * This class provides the {@link #parse parse} function to parse raw
 * javax.sound.midi.MidiMessage objects.  For more information on the
 * javax.sound.midi package, visit the java API website.  For more information
 * on the format and meaning of various midi messages, google
 * "midi file format."  I personally found the website
 * <A href=http://faydoc.tripod.com/formats/mid.htm>http://faydoc.tripod.com/formats/mid.htm</A>
 * very useful.
 * <p>
 * This call can parse most, but not necessarily all midi messages.  You can add
 * to its capabilities by implementing a new {@link RawMidiMessageParser.Parser Parser}
 * for a new kind of midi message.  Simply call {@link #addParser addParser} to
 * add your new parser before calling parse.
 *
 * @author Christine
 */
public class RawMidiMessageParser {

    public static final byte UPPER_NIBBLE = (byte) 0xF0;
    public static final byte LOWER_NIBBLE = (byte) 0x0F;
    // defined by first four bits
    public static final byte NOTE_OFF = (byte) 0x80;
    public static final byte NOTE_ON = (byte) 0x90;
    public static final byte KEY_AFTER_TOUCH = (byte) 0xA0;
    public static final byte CONTROL_CHANGE = (byte) 0xB0;
    public static final byte PROGRAM_CHANGE = (byte) 0xC0;
    public static final byte CHANNEL_AFTER_TOUCH = (byte) 0xD0;
    public static final byte PITCH_WHEEL_CHANGE = (byte) 0xE0;
    public static final byte META_EVENT = (byte) 0xFF;
    public static final byte SET_TRACK_SEQUENCE_NUMBER = (byte) 0x00;
    public static final byte TEXT_EVENT = (byte) 0x01;
    public static final byte COPYRIGHT_TEXT_EVENT = (byte) 0x02;
    public static final byte SEQUENCE_OR_TRACK_NAME = (byte) 0x03;
    public static final byte TRACK_INSTRUMENT_NAME = (byte) 0x04;
    public static final byte LYRIC = (byte) 0x05;
    public static final byte MARKER = (byte) 0x06;
    public static final byte CUE_POINT = (byte) 0x07;
    public static final byte TRACK_END = (byte) 0x2f;
    public static final byte SET_TEMPO = (byte) 0x51;
    public static final byte TIME_SIGNATURE = (byte) 0x58;
    public static final byte KEY_SIGNATURE = (byte) 0x59;
    public static final byte SEQUENCER_SPECIFIC_INFORMATION = (byte) 0x7F;
    public static final byte TIMING_CLOCK = (byte) 0xf8;
    public static final byte START_CURRENT_SEQUENCE = (byte) 0xFA;
    public static final byte CONTINUE_STOPPED_SEQUENCE = (byte) 0xFB;
    public static final byte STOP_A_SEQUENCE = (byte) 0xFC;

    private LinkedList<Parser> parsers = null;

    /**
     * Creates a new RawMidiMessageParser.  This contructor adds all the
     * parsers it already knows about.
     */
    public RawMidiMessageParser() {
        parsers = new LinkedList<>();
        addKnownParsers();
    }


    /**
     * When (@link #parse} is called, it tries each of the parsers in its list
     * in order until it finds a Parser what will parse the current
     * {@link javax.sound.midi.MidiMessage}.  This function adds a new {@link
     * Parser} to the beginning of the list of parsers.  So, it will be the
     * first Parser parse tries.
     *
     * @param parser The new parser you want to add.
     * @see #addParsers
     */
    public void addParser(Parser parser) {
        parsers.addFirst(parser);
    }

    /**
     * When (@link parse} is called, it tries each of the parsers in its list
     * in order until it finds a Parser what will parse the current
     * {@link javax.sound.midi.MidiMessage}.  This function adds the new
     * collection of parsers to the front of its parser list in the same order
     * as the collection's iterator returns the parsers.
     *
     * @param parsers The new parsers you want to add.
     * @see #addParser
     */
    public void addParsers(Collection<Parser> parsers) {
        this.parsers.addAll(0, parsers);
    }


    private void addKnownParsers() {
        parsers.addLast(new ControlChangeParser());
        parsers.addLast(new CopyrightParser());
        parsers.addLast(new CuePointParser());
        parsers.addLast(new KeySignatureParser());
        parsers.addLast(new LyricParser());
        parsers.addLast(new MarkerParser());
        parsers.addLast(new NoteOffParser());
        parsers.addLast(new NoteOnParser());
        parsers.addLast(new ChannelAfterTouchParser());
        parsers.addLast(new PitchWheelChangeParser());
        parsers.addLast(new ProgramChangeParser());
        parsers.addLast(new SetTempoParser());
        parsers.addLast(new TextParser());
        parsers.addLast(new TimeSignatureParser());
        parsers.addLast(new TrackEndParser());
        parsers.addLast(new TrackInstrumentNameParser());
        parsers.addLast(new TrackNameParser());
        parsers.addLast(new TrackSequenceNumberParser());

        // now add the general parsers in case we don't have a specific parser

        parsers.addLast(new MetaCommandParser());
        parsers.addLast(new MidiCommandParser());

    }

    /**
     * Returns a parsed raw MidiMessage.  This function reads the raw bytes in
     * mm and parses them for you.
     *
     * @param mm the midi message you want to parse
     * @return the parsed message
     */
    public MidiCommand parse(javax.sound.midi.MidiMessage mm) {
        boolean done = false;
        Iterator<Parser> it = parsers.iterator();
        MidiCommand pmm = null;

        while (it.hasNext() && !done) {
            pmm = it.next().parse(mm);
            if (pmm != null) {
                done = true;
            }
        }

        return pmm;
    }


    /**
     * Defines a class that can parse some kind of midi message.  Implement this
     * class if you want to parse some kind of new midi message that {@link
     * RawMidiMessageParser} doesn't already parse nicely.
     */
    public interface Parser {

        /**
         * If this parser recognizes mm, it should return a parsed midi
         * message.  Otherwise it should return null.
         *
         * @param mm the message to parse
         * @return a midi command or null
         */
        MidiCommand parse(javax.sound.midi.MidiMessage mm);
    }
}