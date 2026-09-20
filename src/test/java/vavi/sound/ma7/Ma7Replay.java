/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * Replays the port accesses of {@code libM7_EmuSmw7.so}'s driver (what {@code gt.py} logs) into
 * {@link Ma7Chip}, and compares what it renders with what the library rendered.
 * <pre>
 * port log: "&lt;sample&gt; W &lt;port&gt; &lt;data&gt;", "&lt;sample&gt; R &lt;port&gt; &lt;value&gt;", "# Hw_Initialize &lt;fs&gt;"
 * pcm: 16 bit stereo little endian
 * </pre>
 */
public final class Ma7Replay {

    /** @return the samples rendered, 16 bit stereo little endian */
    public static byte[] replay(Ma7Rom rom, Path port, int total, boolean quiet) throws IOException {
        Ma7Chip chip = null;
        long now = 0;
        int readMismatches = 0;
        ByteBuffer out = ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN);
        int[] l = new int[4800], r = new int[4800];
        try (BufferedReader reader = Files.newBufferedReader(port)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# Hw_Initialize")) {
                    int fs = Integer.parseInt(line.split(" ")[2], 16);
                    chip = fs == 48000 ? new Ma7Chip(rom, fs) : null; // terminated at once
                    continue;
                }
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] p = line.split(" ");
                long t = Long.parseLong(p[0]);
                while (now < t) {
                    int n = (int) Math.min(t - now, l.length);
                    chip.generate(l, r, 0, n);
                    for (int i = 0; i < n; i++) {
                        out.putShort((short) l[i]);
                        out.putShort((short) r[i]);
                    }
                    now += n;
                }
                int portNo = Integer.parseInt(p[2], 16), v = Integer.parseInt(p[3], 16);
                if (p[1].equals("W")) {
                    chip.write(portNo, v);
                } else {
                    int got = chip.read(portNo);
                    if (got != v) {
                        if (readMismatches++ < 10 && !quiet) System.err.printf("read mismatch: %s: %02x%n", line, got);
                    }
                }
            }
        }
        while (now < total) {
            int n = (int) Math.min(total - now, l.length);
            chip.generate(l, r, 0, n);
            for (int i = 0; i < n; i++) {
                out.putShort((short) l[i]);
                out.putShort((short) r[i]);
            }
            now += n;
        }
        if (readMismatches > 0 && !quiet) System.err.println("read mismatches: " + readMismatches);
        return out.array();
    }

    /** @return the first sample which differs, -1 none */
    public static int compare(byte[] expected, byte[] actual, boolean quiet) {
        ByteBuffer e = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer a = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
        int n = Math.min(expected.length, actual.length) / 4;
        int first = -1, count = 0, max = 0;
        for (int i = 0; i < n; i++) {
            int el = e.getShort(i * 4), er = e.getShort(i * 4 + 2), al = a.getShort(i * 4), ar = a.getShort(i * 4 + 2);
            if (el != al || er != ar) {
                if (first < 0) first = i;
                count++;
                max = Math.max(max, Math.max(Math.abs(el - al), Math.abs(er - ar)));
            }
        }
        if (!quiet) {
            if (first < 0) {
                System.err.println("same, " + n + " samples");
            } else {
                System.err.printf("differs from %d (block %d, %d samples, max %d)%n", first, first / 48, count, max);
                for (int i = Math.max(0, first - 2); i < Math.min(n, first + 8); i++) {
                    System.err.printf("  %d: %d %d / %d %d%n", i, e.getShort(i * 4), e.getShort(i * 4 + 2), a.getShort(i * 4), a.getShort(i * 4 + 2));
                }
            }
        }
        return first;
    }

    /** replay port pcm [rom] */
    public static void main(String[] args) throws Exception {
        Path port = Path.of(args[0]);
        byte[] expected = Files.readAllBytes(Path.of(args[1]));
        Ma7Rom rom = new Ma7Rom(args.length > 2 ? Path.of(args[2]) : Ma7Rom.path());
        byte[] actual = replay(rom, port, expected.length / 4, false);
        compare(expected, actual, false);
        if (System.getProperty("out") != null) {
            try (OutputStream os = Files.newOutputStream(Path.of(System.getProperty("out")))) {
                os.write(actual);
            }
        }
    }
}
