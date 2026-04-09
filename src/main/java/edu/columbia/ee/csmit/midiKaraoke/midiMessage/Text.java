/*
 * TextEvent.java
 *
 * Created on Nov 1, 2007, 6:51:14 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * Stores text commands.
 *
 * @author Christine
 * @see TextParser
 */
public interface Text extends MetaCommand {

    /**
     * Returns the text in this command.
     *
     * @return the text
     */
    String getText();
}
