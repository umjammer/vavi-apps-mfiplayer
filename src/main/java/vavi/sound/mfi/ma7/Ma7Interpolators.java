/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.ma7;


/**
 * The volume (ARM::CVolIP) and the pan (ARM::CPanIP) of a slot, a level of 5 bits in dB
 * to a linear gain a sample, stepping to a new level over 2^shift samples when it is told to.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-19 nsano initial version <br>
 */
final class Ma7Interpolators {

    private Ma7Interpolators() {}

    /** the shift of a step, 1 / 2^shift of the difference a sample */
    static int shift(int fs) {
        return switch (fs) {
            case 11025 -> 8;
            case 16000, 22050, 24000 -> 9;
            default -> 10;
        };
    }

    /** ARM::CVolIP */
    static final class Volume {

        /** 5 bit to 0x60000000 ~ 0 (asdDBtable) */
        private final int[] db;
        /** (dB >> 20) to the gain (asdLinearTable) */
        private final int[] linear;

        private final int shift;         // +4
        private boolean ramping;         // +8
        private int keyOn;               // +0xc
        private int current;             // +0x10
        private int target;              // +0x14
        private int step;                // +0x18

        Volume(Ma7Rom rom, int fs) {
            db = rom.ints(0x389250, 32);
            linear = rom.ints(0x387a40, 0x601);
            shift = shift(fs);
        }

        /** GetLinearVol */
        int linear(int level) {
            return linear[db[level & 0x1f] >> 20];
        }

        /**
         * Generate
         * @param volume1 the channel's, bit 31: step to it
         * @param volume2 the ex channel's, bit 31: step to it
         * @param single only the ex channel's
         * @param keyOn a key on of the slot, which takes a level at once
         */
        void generate(int volume1, int volume2, boolean single, int keyOn, int[] out, int n) {
            int stepFlags;
            int a;
            if (!single) {
                stepFlags = volume1 & volume2;
                a = db[volume1 & 0x1f];
            } else {
                stepFlags = volume2;
                a = 0;
            }
            int t = a + db[volume2 & 0x1f];
            if (Integer.compareUnsigned(t, 0x60000000) > 0) t = 0x60000000;
            if ((this.keyOn == 0 && keyOn == 1) || stepFlags >= 0) {
                target = t;
                current = t;
                ramping = false;
            } else if (target != t) {
                target = t;
                current &= 0xffff0000;
                if (t != current) {
                    ramping = true;
                    step = (t - current) >> shift;
                } else {
                    ramping = false;
                }
            }
            this.keyOn = keyOn;
            for (int i = 0; i < n; i++) {
                int v;
                if (ramping) {
                    v = current + step;
                    current = v;
                    if (v == target) ramping = false;
                } else {
                    v = current;
                }
                out[i] = linear[v >> 20];
            }
        }
    }

    /** ARM::CPanIP */
    static final class Pan {

        /** 5 bit to dB (asdDBtable) */
        private final int[] db;
        /** (dB >> 20) to the gain (asdLinearTable) */
        private final int[] linear;

        private final int shift;         // +4
        private boolean ramping;         // +8
        private int keyOn;               // +0xc
        private int left = 0x8000;       // +0x10
        private int right = 0x8000;      // +0x14
        private int targetLeft = 0x8000; // +0x18
        private int targetRight = 0x8000;// +0x1c
        private int stepLeft;            // +0x20
        private int stepRight;           // +0x24

        Pan(Ma7Rom rom, int fs) {
            db = rom.ints(0x2219c0, 32);
            linear = rom.ints(0x2201b0, 0x601);
            shift = shift(fs);
        }

        /**
         * Generate
         * @param voice the pan of the voice, 5 bit, 0x10 the center
         * @param channel the channel's, bit 31: step to it
         * @param ex the ex channel's, bit 31: step to it
         * @param exOnly only the ex channel's
         * @param keyOn a key on of the slot
         * @param mono no pan of the voice, 3 dB down
         */
        void generate(int voice, int channel, int ex, boolean exOnly, int keyOn, boolean mono, int[] outLeft, int[] outRight, int n) {
            int stepFlags = ex;
            int p = (ex & 0x1f) - 0x10;
            if (!exOnly) {
                stepFlags = channel & ex;
                p += (channel & 0x1f) - 0x10;
            }
            if (!mono) {
                p += voice - 0x10;
            }
            if (p > 0xf) p = 0xf;
            if (p < -0x10) p = -0x10;
            int l = db[p + 0x10], r = db[0xf - p];
            if ((this.keyOn == 0 && keyOn == 1) || stepFlags >= 0) {
                left = l;
                right = r;
                targetLeft = l;
                targetRight = r;
                ramping = false;
            } else if (targetLeft != l) {
                left &= 0xffff0000;
                targetLeft = l;
                right &= 0xffff0000;
                targetRight = r;
                if (l != left) {
                    ramping = true;
                    stepLeft = (l - left) >> shift;
                    stepRight = (r - right) >> shift;
                } else {
                    ramping = false;
                }
            }
            this.keyOn = keyOn;
            for (int i = 0; i < n; i++) {
                if (ramping) {
                    left += stepLeft;
                    right += stepRight;
                    if (left == targetLeft) ramping = false;
                }
                if (!mono) {
                    outLeft[i] = linear[left >> 20];
                    outRight[i] = linear[right >> 20];
                } else {
                    outLeft[i] = linear[Math.max((left >> 20) - 0x30, 0)];
                    outRight[i] = linear[Math.max((right >> 20) - 0x30, 0)];
                }
            }
        }
    }
}
