/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.awt.Color;
import java.awt.Graphics;
import java.util.HashMap;
import java.util.Map;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.swing.JPanel;


/**
 * PianoRollPane.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-03-18 nsano initial version <br>
 */
public class PianoRollPane extends JPanel {

    Sequence sequence;

    PianoRollPane(Sequence sequence) {
        this.sequence = sequence;
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (sequence == null) {
            return;
        }

        int width = getWidth();
        int height = getHeight();

        long maxTick = sequence.getTickLength();
        if (maxTick == 0) {
            maxTick = 1; // Prevent division by zero
        }

        int maxPitch = 127;
        int minPitch = 0;

        double tickX = (double) width / maxTick;
        double pitchY = (double) height / (maxPitch - minPitch + 1);

        // Grid view
        g.setColor(Color.LIGHT_GRAY);
        
        // Vertical scale (pitches on Y-axis)
        for (int pitch = minPitch; pitch <= maxPitch; pitch++) {
            int y = (int) ((maxPitch - pitch) * pitchY);
            g.drawLine(0, y, width, y);
        }

        // Horizontal tick (time on X-axis)
        int resolution = sequence.getResolution();
        if (resolution > 0) {
            for (long tick = 0; tick <= maxTick; tick += resolution) {
                int x = (int) (tick * tickX);
                g.drawLine(x, 0, x, height);
            }
        }

        // Put box colored by channel
        Color[] channelColors = {
            Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA,
            Color.ORANGE, Color.CYAN, Color.PINK, Color.YELLOW,
            new Color(128, 0, 0), new Color(0, 128, 0), new Color(0, 0, 128), new Color(128, 0, 128),
            new Color(128, 128, 0), new Color(0, 128, 128), new Color(255, 128, 128), new Color(128, 255, 128)
        };

        for (Track track : sequence.getTracks()) {
            Map<Integer, Long> noteOns = new HashMap<>();

            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();

                if (message instanceof ShortMessage) {
                    ShortMessage sm = (ShortMessage) message;
                    int command = sm.getCommand();
                    int channel = sm.getChannel();
                    int pitch = sm.getData1();
                    int velocity = sm.getData2();

                    int key = (channel << 8) | pitch;

                    if (command == ShortMessage.NOTE_ON && velocity > 0) {
                        noteOns.put(key, event.getTick());
                    } else if (command == ShortMessage.NOTE_OFF || (command == ShortMessage.NOTE_ON && velocity == 0)) {
                        Long startTick = noteOns.get(key);
                        if (startTick != null) {
                            long endTick = event.getTick();
                            noteOns.remove(key);

                            int x = (int) (startTick * tickX);
                            int w = Math.max(1, (int) ((endTick - startTick) * tickX));
                            int y = (int) ((maxPitch - pitch) * pitchY);
                            int h = Math.max(1, (int) pitchY);

                            g.setColor(channelColors[channel % 16]);
                            g.fillRect(x, y, w, h);
                            g.setColor(Color.BLACK);
                            g.drawRect(x, y, w, h);
                        }
                    }
                }
            }
        }
    }
}