/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.lang.System.getLogger;


/**
 * The rom of the yamaha MA-7, read out of the installed {@code libM7_EmuSmw7.so} (the MA-7
 * emulator of yamaha's android app "着信音設定", arm64), nothing of it is distributed with this.
 * <p>
 * The library is an elf of the one build known ({@link #SIZE}, {@link #CRC}), and what is read
 * of it is at its virtual addresses: the 64 KB of the wave rom, the voice rom the middleware
 * keeps, and the tables of the synthesizers. A table of pointers is resolved by the relocations
 * of the library, as the dynamic linker does.
 * <p>
 * system property
 * <li>{@code vavi.sound.mfi.ma7.path} ... the library, or the apk of the app it is in
 * ({@code lib/arm64-v8a/libM7_EmuSmw7.so} of it), default {@code tmp/libM7_EmuSmw7.so}</li>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public final class Ma7Rom {

    private static final Logger logger = getLogger(Ma7Rom.class.getName());

    /** the library */
    public static final String SO = "libM7_EmuSmw7.so";
    /** where the library is in the apk */
    static final String APK_ENTRY = "lib/arm64-v8a/" + SO;

    /** the size of the build the addresses are of */
    static final int SIZE = 4661808;
    /** the crc32 of the build the addresses are of */
    static final int CRC = 0x715b0baa;

    /** the image as it is mapped, the relocations applied */
    private final ByteBuffer image;

    /** the pointers of the image, virtual address to what it points */
    private final Map<Integer, Long> pointers = new HashMap<>();

    /** the size of the image as it is mapped */
    private static final int IMAGE_SIZE = 0x680000;

    public Ma7Rom(Path path) throws IOException {
        byte[] so = read(path);
        CRC32 crc = new CRC32();
        crc.update(so);
        if (so.length != SIZE || (int) crc.getValue() != CRC) {
            throw new IOException("unknown build of %s: %d bytes, crc 0x%08x, %d bytes and 0x%08x is known"
                    .formatted(path, so.length, crc.getValue(), SIZE, CRC));
        }
        ByteBuffer elf = ByteBuffer.wrap(so).order(ByteOrder.LITTLE_ENDIAN);
        byte[] mapped = new byte[IMAGE_SIZE];
        // program headers, the loadable segments
        long phoff = elf.getLong(0x20);
        int phentsize = elf.getShort(0x36) & 0xffff, phnum = elf.getShort(0x38) & 0xffff;
        for (int i = 0; i < phnum; i++) {
            int o = (int) phoff + i * phentsize;
            if (elf.getInt(o) != 1) continue; // PT_LOAD
            int offset = (int) elf.getLong(o + 8), va = (int) elf.getLong(o + 0x10), size = (int) elf.getLong(o + 0x20);
            System.arraycopy(so, offset, mapped, va, size);
        }
        image = ByteBuffer.wrap(mapped).order(ByteOrder.LITTLE_ENDIAN);
        // section headers, the relative relocations
        long shoff = elf.getLong(0x28);
        int shentsize = elf.getShort(0x3a) & 0xffff, shnum = elf.getShort(0x3c) & 0xffff;
        for (int i = 0; i < shnum; i++) {
            int o = (int) shoff + i * shentsize;
            if (elf.getInt(o + 4) != 4) continue; // SHT_RELA
            int offset = (int) elf.getLong(o + 0x18), size = (int) elf.getLong(o + 0x20);
            for (int r = offset; r < offset + size; r += 24) {
                long where = elf.getLong(r), info = elf.getLong(r + 8), addend = elf.getLong(r + 16);
                if ((info & 0xffffffffL) == 1027) { // R_AARCH64_RELATIVE
                    image.putLong((int) where, addend);
                    pointers.put((int) where, addend);
                }
            }
        }
logger.log(Level.DEBUG, "rom: " + path);
    }

    /** the library, or the one in an apk */
    private static byte[] read(Path path) throws IOException {
        if (path.getFileName().toString().toLowerCase().endsWith(".apk")) {
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals(APK_ENTRY)) {
                        return zis.readAllBytes();
                    }
                }
            }
            throw new IOException("no " + APK_ENTRY + " in " + path);
        } else {
            try (InputStream is = Files.newInputStream(path)) {
                return is.readAllBytes();
            }
        }
    }

    /** a byte, unsigned */
    public int u8(int va) { return image.get(va) & 0xff; }
    /** a byte, signed */
    public int s8(int va) { return image.get(va); }
    /** a 16 bit word, unsigned */
    public int u16(int va) { return image.getShort(va) & 0xffff; }
    /** a 16 bit word, signed */
    public int s16(int va) { return image.getShort(va); }
    /** a 32 bit word */
    public int s32(int va) { return image.getInt(va); }
    /** a pointer, the relocation applied */
    public int pointer(int va) {
        Long p = pointers.get(va);
        if (p == null) throw new IllegalArgumentException("no pointer at 0x%x".formatted(va));
        return (int) (long) p;
    }

    /** @return {@code count} 32 bit words from {@code va} */
    public int[] ints(int va, int count) {
        int[] a = new int[count];
        for (int i = 0; i < count; i++) a[i] = image.getInt(va + i * 4);
        return a;
    }

    /** @return {@code count} bytes from {@code va} */
    public byte[] bytes(int va, int count) {
        byte[] a = new byte[count];
        image.get(va, a);
        return a;
    }

    // ----

    /** the property naming the library */
    public static final String PATH_KEY = "vavi.sound.mfi.ma7.path";

    private static volatile Ma7Rom instance;

    /** where {@link #PATH_KEY} points */
    public static Path path() {
        return Path.of(System.getProperty(PATH_KEY, "tmp/" + SO));
    }

    /** from where {@link #PATH_KEY} points */
    public static Ma7Rom getInstance() throws IOException {
        if (instance == null) {
            synchronized (Ma7Rom.class) {
                if (instance == null) {
                    instance = new Ma7Rom(path());
                }
            }
        }
        return instance;
    }

    /** is there a rom to play with? */
    public static boolean isAvailable() {
        return Files.exists(path());
    }
}
