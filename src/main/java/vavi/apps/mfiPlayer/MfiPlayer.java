/*
 * http://www.xucker.jpn.org/ood/java/imelody/
 */

package vavi.apps.mfiPlayer;

import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.prefs.Preferences;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JToolBar;
import javax.swing.UIDefaults;
import javax.swing.UIManager;

import vavi.util.Debug;
import vavi.util.RegexFileFilter;


/**
 * MFi Player
 *
 * @author Akihito Miyazaki
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 001220 akihito original version <br>
 *          1.00 020628 nsano refine <br>
 *          1.01 030727 nsano refine <br>
 */
public class MfiPlayer {

    /** */
    private static final Preferences prefs = Preferences.userNodeForPackage(MfiPlayer.class);

    /** */
    private Sequencer sequencer;

    /** Creates new DefaultPlayer */
    private MfiPlayer(String[] args) throws Exception {

        sequencer = MidiSystem.getSequencer();
        sequencer.open();
Debug.println("sequencer: " + sequencer);
Debug.println("synthesizer: " + MidiSystem.getSynthesizer());

        for (String arg : args) {
            File file = new File(arg);
            if (file.exists()) {
                if (!file.isDirectory()) {
Debug.println(file);
                    open(file);
                    play();
                }
            }
        }

        JFrame frame = new JFrame("MFi Player");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JMenuBar menuBar = new JMenuBar();

        JMenu menu = new JMenu("File");
        menu.setMnemonic(KeyEvent.VK_F);
        menuBar.add(menu);

        JMenuItem menuItem = new JMenuItem(openAction);
        menuItem.setMnemonic(KeyEvent.VK_O);
        menu.add(menuItem);

        menu.addSeparator();

        menuItem = new JMenuItem(exitAction);
        menuItem.setMnemonic(KeyEvent.VK_X);
        menu.add(menuItem);

        menu = new JMenu("Play");
        menu.setMnemonic(KeyEvent.VK_P);
        menuBar.add(menu);

        menuItem = new JMenuItem(playAction);
        menuItem.setMnemonic(KeyEvent.VK_P);
        menu.add(menuItem);

        menuItem = new JMenuItem(stopAction);
        menuItem.setMnemonic(KeyEvent.VK_S);
        menu.add(menuItem);

        frame.setJMenuBar(menuBar);

        JToolBar toolBar = new JToolBar();
        toolBar.add(openAction);
        toolBar.add(exitAction);
        toolBar.add(playAction);
        toolBar.add(stopAction);

        frame.getContentPane().add(toolBar);

        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) {
                prefs.putInt("x", frame.getX());
                prefs.putInt("y", frame.getY());
            }
        });
        int x = prefs.getInt("x", 100);
        int y = prefs.getInt("y", 100);
        frame.setLocation(x, y);

        frame.pack();
        frame.setVisible(true);
    }

    /** */
    private void open(File file) throws Exception {
        InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()));
        Sequence sequence = MidiSystem.getSequence(is);
        sequencer.setSequence(sequence);
    }

    /** */
    private void play() {
        if (!sequencer.isRunning()) {
            sequencer.start();
        }
    }

    /** */
    private void stop() {
Debug.println("isRunning: " + sequencer.isRunning());
//        if (sequencer.isRunning()) {
            sequencer.stop();
//        }
    }

    /** */
    private void close() {
        if (sequencer.isOpen()) {
            sequencer.close();
        }
    }

    /** */
    private Action playAction = new AbstractAction(
        "Play",
        (ImageIcon) UIManager.get("mfiPlayer.playIcon")) {
        public void actionPerformed(ActionEvent ev) {
            try {
                play();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    /** */
    private Action stopAction = new AbstractAction(
        "Stop",
        (ImageIcon) UIManager.get("mfiPlayer.stopIcon")) {
        public void actionPerformed(ActionEvent ev) {
            try {
                stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    /** */
    private static final RegexFileFilter fileFilter =
        new RegexFileFilter(".+\\.((mld)|(mid)|(mmf)|(MID))", "MIDI,MFi,SMAF File");

    /** */
    private Action openAction = new AbstractAction(
        "Open",
        (ImageIcon) UIManager.get("mfiPlayer.openIcon")) {
        final JFileChooser fc;
        {
            fc = new JFileChooser();
            fc.setFileFilter(fileFilter);
        }
        @Override public void actionPerformed(ActionEvent ev) {
            try {
                fc.setCurrentDirectory(new File(prefs.get("cwd", System.getProperty("user.home"))));
                if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    File file = fc.getSelectedFile();

                    stop();
                    open(file);
                    prefs.put("cwd", fc.getCurrentDirectory().getPath());
                    play();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    /** */
    private Action exitAction = new AbstractAction(
        "Exit",
        (ImageIcon) UIManager.get("mfiPlayer.exitIcon")) {
        @Override public void actionPerformed(ActionEvent ev) {
            stop();
            close();
//            System.exit(0);
        }
    };

    /* */
    static {
        try {
            Toolkit t = Toolkit.getDefaultToolkit();
            UIDefaults table = UIManager.getDefaults();
            final Class<?> c = MfiPlayer.class;
            final String base = "/toolbarButtonGraphics/";

            String name = "mfiPlayer.openIcon";
            String icon = base + "general/Open24.gif";
            table.put(name, new ImageIcon(t.getImage(c.getResource(icon))));
            name = "mfiPlayer.exitIcon";
            icon = base + "general/Stop24.gif";
            table.put(name, new ImageIcon(t.getImage(c.getResource(icon))));
            name = "mfiPlayer.playIcon";
            icon = base + "media/Play24.gif";
            table.put(name, new ImageIcon(t.getImage(c.getResource(icon))));
            name = "mfiPlayer.stopIcon";
            icon = base + "media/Stop24.gif";
            table.put(name, new ImageIcon(t.getImage(c.getResource(icon))));
        } catch (Exception e) {
Debug.printStackTrace(e);
            throw new IllegalStateException(e);
        }
    }

    /** */
    public static void main(String[] args) throws Exception {
        new MfiPlayer(args);
    }
}

/* */
