/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


/**
 * Plays the events {@code gt.py} takes with {@link Ma7Driver}, and writes the pcm and the port log
 * to compare with what the library did.
 * <pre>
 * events: "&lt;sample&gt; &lt;hex bytes...&gt;", "&lt;sample&gt; gen"
 * </pre>
 */
public final class Ma7DriverCompare {

    private static String lists(Ma7Dva.Slots s) {
        StringBuilder sb = new StringBuilder();
        for (Ma7Dva.Node n = s.head.next; n != s.tail; n = n.next) {
            sb.append(n == s.mid ? "|" : n.slot + "" + "RND".charAt(n.state)).append(' ');
        }
        return sb.toString().trim();
    }

    /** events out_prefix total [so] */
    public static void main(String[] args) throws Exception {
        Ma7Rom rom = new Ma7Rom(args.length > 3 ? Path.of(args[3]) : Ma7Rom.path());
        int total = Integer.parseInt(args[2]);
        List<String> log = new ArrayList<>();
        List<String> lists = System.getProperty("lists") != null ? new ArrayList<>() : null;
        long[] now = {0};
        Ma7Chip chip = new Ma7Chip(rom, 48000);
        log.add("# Hw_Initialize bb80");
        Ma7Driver driver = new Ma7Driver(rom, chip, s -> log.add(now[0] + " " + s));
        ByteBuffer out = ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN);
        int[] l = new int[4800], r = new int[4800];
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            line = line.split("#")[0].trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            long t = Long.parseLong(p[0]);
            while (now[0] < t) {
                int n = (int) Math.min(t - now[0], l.length);
                chip.generate(l, r, 0, n);
                for (int i = 0; i < n; i++) { out.putShort((short) l[i]); out.putShort((short) r[i]); }
                now[0] += n;
            }
            if (p[1].equals("gen")) continue;
            byte[] d = new byte[p.length - 1];
            for (int i = 0; i < d.length; i++) d[i] = (byte) Integer.parseInt(p[i + 1], 16);
            if ((d[0] & 0xff) == 0xf0) {
                driver.exclusive(d);
            } else {
                driver.message(d[0] & 0xff, d.length > 1 ? d[1] : 0, d.length > 2 ? d[2] : 0);
            }
            if (lists != null) lists.add(line + " | " + lists(driver.dva.fm) + " | " + lists(driver.dva.wt));
        }
        while (now[0] < total) {
            int n = (int) Math.min(total - now[0], l.length);
            chip.generate(l, r, 0, n);
            for (int i = 0; i < n; i++) { out.putShort((short) l[i]); out.putShort((short) r[i]); }
            now[0] += n;
        }
        try (OutputStream os = Files.newOutputStream(Path.of(args[1] + ".pcm"))) {
            os.write(out.array());
        }
        Files.write(Path.of(args[1] + ".port"), log);
        if (lists != null) Files.write(Path.of(System.getProperty("lists")), lists);
    }
}
