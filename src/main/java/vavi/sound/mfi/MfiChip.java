/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Track;

import vavi.sound.midi.MidiConstants.MetaEvent;

import static java.lang.System.getLogger;
import static vavi.sound.mfi.vavi.sequencer.MachineDependentSequencer.MFi_SYSEX_FUNCTION_ID_MACHINE_DEPENDENT;
import static vavi.sound.mfi.vavi.sub.AinfChunk.META_FUNCTION_ID_AudioEngine;
import static vavi.sound.midi.VaviMidiDeviceProvider.MANUFACTURER_ID;
import static vavi.sound.mobile.MobileExclusive.MIDI_SYSEX_FUNCTION_ID_PACKED;
import static vavi.sound.mobile.MobileExclusive.unpack;


/**
 * The sound chip an mfi was made for, and how it is found out.
 * <p>
 * An mfi is written for the sound source of the phone it was made for, and that source is one of
 * three families: Yamaha's FM (MA-2 ~ MA-7), FueTrek's software pcm (48A, 64B, PCM128) or Rohm's
 * pcm (BU8788KN, BU8709KN). The file never says which, so it is worked out from, in order,
 * <ol>
 *  <li>the "ainf" audio formats: 0x80 rohm, 0x81 fuetrek, 0x82 yamaha adpcm - only an mfi 4 or
 *      later with adpcm has one, but it is certain</li>
 *  <li>a phone model in "supt" ({@code MFICONV_N505I}), looked up in {@code models.csv}</li>
 *  <li>a Yamaha part in "supt" ({@code MA3Plugin_N}, {@code SCP-MA5-N-Plugin})</li>
 *  <li>the maker: the vendor letter of "supt" ({@code SH_PlugIn}, {@code MFi4PlugIn_F}) or the
 *      vendor nibble of the machine dependent messages, together with the mfi version, see
 *      {@link Vendor#chip(int, int)}</li>
 * </ol>
 * The version table is the one of the "MFi" sheet of the phone database, see {@code models.csv}.
 * A file of none of those (made by a hobbyist's tool, 15000 of 15000 of "UnGoodMLD") gets
 * {@code mdplayer.mfi.chip.default}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
public enum MfiChip {
    /** MA-2 ~ MA-7, fm */
    YAMAHA("YAMAHA"),
    /** software pcm, docomo's UCS */
    FUETREK("FUETREK"),
    /** BU8788KN, BU8709KN, pcm */
    ROHM("ROHM");

    private static final Logger logger = getLogger(MfiChip.class.getName());

    /** system property: the chip of a file which says nothing, default {@link #YAMAHA} */
    public static final String DEFAULT_KEY = "mdplayer.mfi.chip.default";

    /** the name shown, upper case as every chip name is */
    public final String label;

    MfiChip(String label) {
        this.label = label;
    }

    /** the maker of a phone, the high nibble of the vendor | carrier byte */
    public enum Vendor {
        NEC(0x10, "N"),
        FUJITSU(0x20, "F"),
        SONY(0x30, "SO"),
        PANASONIC(0x40, "P"),
        MITSUBISHI(0x60, "D"),
        SHARP(0x70, "SH");

        public final int id;
        public final String letter;

        Vendor(int id, String letter) {
            this.id = id;
            this.letter = letter;
        }

        static Vendor byId(int vendorCarrier) {
            for (Vendor v : values()) {
                if (v.id == (vendorCarrier & 0xf0)) return v;
            }
            return null;
        }

        static Vendor byLetter(String letter) {
            for (Vendor v : values()) {
                if (v.letter.equalsIgnoreCase(letter)) return v;
            }
            return null;
        }

        /**
         * The chip of the phones of this maker of a generation, as the database has it. A file
         * is made for the newest phones of its version, so where a generation is mixed the
         * later ones are taken.
         *
         * @param version "vers", 0x0301 for 3.1, -1: not told
         */
        public MfiChip chip(int version, int generation) {
            return switch (this) {
                case NEC -> YAMAHA; // MA-2 ~ MA-7 all along
                // SO504i ~ SO213i: MA-3, SO505iS on: 64B, PCM128
                case SONY -> generation >= 4 ? FUETREK : YAMAHA;
                // F503i: MA-2, F504i ~ F901iS: BU8788KN, BU8709KN, F903i: PCM128
                case FUJITSU -> generation == 1 ? YAMAHA : generation >= 5 ? FUETREK : ROHM;
                // P503i, P504i: rohm, P505i, P506iC: 48A, P900i ~ P901iS: BU8709KN, P903i: PCM128
                // D503i, D504i: rohm, D505i ~ D900i: 48A, D901i, D701i: BU8709KN, D903i: PCM128
                case PANASONIC, MITSUBISHI -> switch (generation) {
                    case 3, 5 -> FUETREK;
                    case -1 -> FUETREK;
                    default -> ROHM;
                };
                // SH251i, SH505i: BU8788KN, BU8709KN, SH252i (3.1) on: 64B, PCM128
                case SHARP -> version >= 0 && version <= 0x0300 ? ROHM : FUETREK;
            };
        }
    }

    /** what was found out and why */
    public record Detection(MfiChip chip, String part, String reason) {
        /** the chip and the part when it is known, upper case: {@code "YAMAHA MA-3"} */
        public String name() {
            return (chip.label + (part != null && !part.isEmpty() ? " " + part : "")).toUpperCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return chip + (part != null && !part.isEmpty() ? " (" + part + ")" : "") + ": " + reason;
        }
    }

    /** model (upper case) → chip, part */
    private static final Map<String, String[]> models = new HashMap<>();

    static {
        try (InputStream is = MfiChip.class.getResourceAsStream("models.csv")) {
            if (is == null) throw new IllegalStateException("no models.csv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] cols = line.split(",", -1);
                models.put(cols[0].strip().toUpperCase(Locale.ROOT), new String[] {cols[1].strip(), cols.length > 2 ? cols[2].strip() : ""});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** a docomo model number: maker letters, 2 ~ 4 digits, the rest */
    private static final Pattern MODEL = Pattern.compile("(SH|SO|SA|KO|N|F|P|D|R|T|M)(\\d{2,4}I?[A-Z]{0,3})");

    /** Yamaha parts named by the authoring plugins */
    private static final Pattern YAMAHA_PART = Pattern.compile("(?:^|[^A-Z0-9])(MA-?[2357])(?:[^0-9]|$)");

    /** the phone model of this name, as {@code models.csv} has it, nullable */
    static Detection byModel(String model) {
        String[] entry = models.get(model.toUpperCase(Locale.ROOT));
        return entry == null ? null : new Detection(valueOf(entry[0]), entry[1], "model " + model);
    }

    /** search condition */
    public record Condition(int[] audioFormats, String support, List<Integer> vendorCarriers, int version, int majorVersion) {

        @Override
        public String toString() {
            return new StringJoiner(", ", Condition.class.getSimpleName() + "[", "]")
                    .add("audioFormats=" + Arrays.toString(audioFormats))
                    .add("support='" + support + "'")
                    .add("vendorCarriers=" + vendorCarriers)
                    .add("version=" + version)
                    .add("majorVersion=" + majorVersion)
                    .toString();
        }

        /** from vavi converted midi sequence */
        public static Condition create(Sequence sequence) {
            List<Integer> audioFormats = new ArrayList<>();
            String support = null;
            List<Integer> vendorCarriers = new ArrayList<>();
            int version = -1;

            Track[] tracks = sequence.getTracks();
            for (Track track : tracks) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    if (event.getMessage() instanceof MetaMessage metaMessage) {
                        if (metaMessage.getType() == MetaEvent.META_MARKER.number()) {
                            support = new String(metaMessage.getData());
logger.log(Level.TRACE, "support: " + support);
                        } else if (metaMessage.getType() == MetaEvent.META_MACHINE_DEPEND.number()) {
                            byte[] data = metaMessage.getData();
                            if (data.length > 2 && data[1] == META_FUNCTION_ID_AudioEngine) {
                                int format = (data[2] & 0xff) * 0x100 + (data[3] & 0xff);
logger.log(Level.TRACE, "audioFormats[%d]: %02x".formatted(audioFormats.size(), format));
                                audioFormats.add(format);
                            }
                        } else if (metaMessage.getType() == MetaEvent.META_TEXT_EVENT.number()) {
                            String[] texts = new String(metaMessage.getData()).split(": ");
                            String tag = texts[0];
                            if (texts.length > 1) {
                                String text = texts[1];
                                switch (tag) {
                                    case "vers" -> {
                                        version = Integer.parseInt(text, 16);
logger.log(Level.TRACE, "version: " + version);
                                    }
                                }
                            }
                        }
                    } else if (event.getMessage() instanceof SysexMessage sysexMessage) {
                        int status = sysexMessage.getStatus();
                        if (status == 0xf0) {
                            byte[] data = sysexMessage.getData();
                            if ((data[0] & 0xff) == MANUFACTURER_ID && (data[1] & 0xff) == MIDI_SYSEX_FUNCTION_ID_PACKED) {
                                byte[] exclusive = unpack(data);
                                if ((exclusive[0] & 0xff) == MANUFACTURER_ID && (exclusive[1] & 0xff) == MFi_SYSEX_FUNCTION_ID_MACHINE_DEPENDENT) {
logger.log(Level.TRACE, "vendorCarriers[%d]: %02x".formatted(vendorCarriers.size(), exclusive[2 + 5] & 0xf));
                                    vendorCarriers.add(exclusive[2 + 5] & 0xff);
                                }
                            }
                        }
                    }
                }
            }

            return new Condition(audioFormats.stream().mapToInt(i -> i).toArray(), support, vendorCarriers, version, version < 0 ? -1 : version >> 8);
        }
    }

    /** finds out the chip of the condition */
    public static Detection detect(Condition condition) {
        // 1. ainf
        for (int format : condition.audioFormats()) {
            switch (format) {
                case 0x80 -> { return new Detection(ROHM, null, "ainf adpcm type 1"); }
                case 0x81 -> { return new Detection(FUETREK, null, "ainf adpcm type 2"); }
                case 0x82 -> { return new Detection(YAMAHA, null, "ainf adpcm type 3"); }
                default -> {}
            }
        }

        String supt = condition.support();
        MfiChip.Vendor vendor = null;
        String vendorFrom = null;
        if (supt != null) {
            String upper = supt.toUpperCase(Locale.ROOT);
            // 2. a model
            for (String token : upper.split("[^A-Z0-9]+")) {
                Matcher m = MODEL.matcher(token);
                if (m.matches()) {
                    MfiChip.Detection d = byModel(token);
                    if (d != null) return new Detection(d.chip, d.part, "supt \"" + supt + "\": " + d.reason);
                }
            }
            // 3. a yamaha part
            Matcher m = YAMAHA_PART.matcher(upper);
            if (m.find()) {
                return new Detection(YAMAHA, m.group(1), "supt \"" + supt + "\"");
            }
            // 4. the maker, a token of the letters alone
            for (String token : supt.split("[^A-Za-z0-9]+")) {
                Vendor v = Vendor.byLetter(token);
                if (v != null) {
                    vendor = v;
                    vendorFrom = "supt \"" + supt + "\"";
                    break;
                }
            }
        }
        if (vendor == null) {
            for (int vc : condition.vendorCarriers()) {
                Vendor v = Vendor.byId(vc);
                if (v != null) {
                    vendor = v;
                    vendorFrom = "machine dependent %02x".formatted(vc);
                    break;
                }
            }
        }
        int version = condition.version();
        int generation = condition.majorVersion();
        if (vendor != null) {
            return new Detection(vendor.chip(version, generation), null,
                    vendorFrom + " → " + vendor + ", vers " + (version < 0 ? "-" : "%04x".formatted(version)));
        }
        if (condition.vendorCarriers().contains(0x01) && generation >= 5) {
            // "MFi5PlugIn_DoCoMo", the 903i generation, PCM128 but the NEC ones
            return new Detection(FUETREK, "PCM128", "machine dependent 01, mfi 5");
        }

        MfiChip chip;
        try {
            chip = valueOf(System.getProperty(DEFAULT_KEY, YAMAHA.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
logger.log(Level.WARNING, "unknown " + DEFAULT_KEY + ": " + e.getMessage());
            chip = YAMAHA;
        }
        return new Detection(chip, null, "nothing told, the default");
    }
}
