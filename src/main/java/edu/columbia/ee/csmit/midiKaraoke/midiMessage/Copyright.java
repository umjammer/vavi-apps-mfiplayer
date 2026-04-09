/*
 * Copyright.java
 *
 * Created on Nov 1, 2007, 6:52:33 PM
 *
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.columbia.ee.csmit.midiKaraoke.midiMessage;

/**
 * A copyright midi meta command.
 *
 * @author Christine
 * @see CopyrightParser
 */
public interface Copyright extends MetaCommand {

    /**
     * Returns the copyright information.
     *
     * @return the copyright text in this command
     */
    public String getCopyright();
}
