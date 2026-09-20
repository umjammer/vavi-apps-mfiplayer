/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ma7;

import java.util.Arrays;


/**
 * The slot allocator of the driver (YAMAHA::MaDva), for fm and wave table slots each.
 * <p>
 * the slots of a kind are on a list, "head, released slots..., mid, sounding slots..., tail",
 * the oldest first. a new note takes the oldest released slot, or steals the oldest sounding one.
 * <p>
 * a channel is poly (1), a drum (2) or mono (0), a note of a poly channel is keyed by
 * {@code channel + key * 0x40}, which the key table maps to the slot, bit 7: sounding, bit 6: wave table.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-20 nsano initial version <br>
 */
final class Ma7Dva {

    /** the states of a slot (+0x18) */
    static final int RELEASED = 0, ON = 1, DEAD = 2;

    /** a slot on a list */
    static final class Node {
        Node next, prev;
        /** +0x10, channel + key * 0x40 or channel (mono) */
        int key;
        /** +0x14, 1: keyed by the key table, 0: by the channel (mono) */
        int keyed = 1;
        /** +0x18 */
        int state = DEAD;
        /** +0x1c */
        final int slot;

        Node(int slot) {
            this.slot = slot;
        }

        void unlink() {
            prev.next = next;
            next.prev = prev;
        }

        void insertBefore(Node n) {
            prev = n.prev;
            n.prev.next = this;
            n.prev = this;
            next = n;
        }

        void insertAfter(Node n) {
            next = n.next;
            n.next.prev = this;
            n.next = this;
            prev = n;
        }
    }

    /** the list of a kind of slots */
    static final class Slots {
        final Node[] nodes;
        final Node head = new Node(-1), mid = new Node(-1), tail = new Node(-1);

        Slots(int n) {
            nodes = new Node[n];
            Node p = head;
            for (int i = 0; i < n; i++) {
                nodes[i] = new Node(i);
                p.next = nodes[i];
                nodes[i].prev = p;
                p = nodes[i];
            }
            p.next = mid;
            mid.prev = p;
            mid.next = tail;
            tail.prev = mid;
        }

        /** the oldest released one, or the oldest sounding one */
        Node oldest() {
            Node n = head.next;
            return n == mid ? n.next : n;
        }
    }

    /** the result of getting a slot (_MADVA_GETSLOTINFO) */
    static final class SlotInfo {
        /** 1: a new one, 2: the same one again (mono), 3: a new one and the other kind of one to be damped */
        int status;
        int slot;
        int other;
    }

    /** the channel's (+0x10c8 + channel * 0xc) */
    private static final class Channel {
        /** 0: mono, 1: poly, 2: drum */
        int mode = 1;
        /** 0x40: a mono note is on a wave table slot */
        int wt;
        /** the slot of a mono note */
        int slot;
        /** the notes on the slot of a mono note */
        int count;
    }

    /** exclusive groups of drum keys (0x38c250) */
    private final int[] altGroups;
    /** the keys sharing a slot (0x38c2d0) */
    private final int[] altKeys;

    /** MaDva_Initialize's argument */
    private final int mode;
    final Slots fm, wt;
    private final Channel[] channels = new Channel[64];
    /** +0x13c8 */
    private final int[] keyTable = new int[0x2000];
    /** +0x33c8 */
    private final int[] altGroupKey = new int[6];
    /** +0x33e0 */
    private final int[] altChannelKey = new int[6 * 0x40];

    Ma7Dva(Ma7Rom rom, int mode, int fmSlots, int wtSlots) {
        this.mode = mode;
        altGroups = new int[128];
        altKeys = new int[128];
        for (int i = 0; i < 128; i++) {
            altGroups[i] = rom.u8(0x38c250 + i);
            altKeys[i] = rom.u8(0x38c2d0 + i);
        }
        fm = new Slots(fmSlots);
        wt = new Slots(wtSlots);
        for (int i = 0; i < channels.length; i++) channels[i] = new Channel();
        Arrays.fill(altGroupKey, -1);
        Arrays.fill(altChannelKey, -1);
    }

    /** a sounding slot taken from another note */
    private void steal(Node n) {
        if (n.state == ON) {
            if (n.keyed == 1) {
                keyTable[n.key] &= 0x7f;
            } else {
                channels[n.key & 0x3f].count = 0;
            }
        }
    }

    /** a slot of the other kind released */
    private static void release(Slots s, Node n, SlotInfo info) {
        info.status = 3;
        info.other = n.slot;
        if (n.state == ON) {
            n.state = RELEASED;
            n.unlink();
            n.insertBefore(s.mid);
        }
    }

    /**
     * MaDva_GetFmSlot, MaDva_GetWtSlot
     * @param isWt wave table or fm
     * @param mode 0: mono, 1: poly, 2: drum
     */
    SlotInfo getSlot(boolean isWt, int seq, int ch, int key, int mode) {
        Slots s = isWt ? wt : fm, o = isWt ? fm : wt;
        int flag = isWt ? 0x40 : 0;
        SlotInfo info = new SlotInfo();
        info.status = 1;
        int id = ch + seq * 0x10;
        Channel c = channels[id];
        int k;
        if (mode != 1 && mode != 2) {
            if (c.mode == 0 && c.count >= 1) {
                if (c.wt == flag) {
                    // the same slot again
                    Node n = s.nodes[c.slot];
                    info.status = 2;
                    info.slot = n.slot;
                    c.count++;
                    n.state = ON;
                    n.unlink();
                    n.insertBefore(s.tail);
                    return info;
                }
                release(o, o.nodes[c.slot], info);
            }
            k = id + key * 0x40;
            keyTable[k] = 0;
            Node n = s.oldest();
            info.slot = n.slot;
            steal(n);
            c.mode = 0;
            c.wt = flag;
            c.count = 1;
            n.state = ON;
            n.unlink();
            n.insertBefore(s.tail);
            n.key = k;
            n.keyed = 0;
            c.slot = n.slot;
            return info;
        }
        if (mode == 2 && altGroups[key] != 0) {
            int alt = altGroups[key];
            if (this.mode != 1) {
                altChannelKey[id + alt * 0x40] = id + key * 0x40;
                k = id + altKeys[key] * 0x40;
            } else {
                k = id + key * 0x40;
                altGroupKey[alt] = k;
            }
        } else {
            k = id + key * 0x40;
        }
        if (c.mode == 0 && c.count > 0) {
            c.count = 0;
            if (c.wt == flag) {
                // a mono note goes on as a poly one
                c.mode = mode;
                Node n = s.nodes[c.slot];
                n.key = k;
                n.keyed = 1;
                info.slot = n.slot;
                n.state = ON;
                n.unlink();
                n.insertBefore(s.tail);
                keyTable[k] = n.slot | 0x80 | flag;
                return info;
            }
            if (isWt) {
                // the original's: the wave table slot of the index goes on the fm list
                Node n = wt.nodes[c.slot];
                info.status = 3;
                info.other = n.slot;
                if (n.state == ON) {
                    n.state = RELEASED;
                    n.unlink();
                    n.insertBefore(fm.mid);
                }
            } else {
                release(o, o.nodes[c.slot], info);
            }
        }
        c.mode = mode;
        int v = keyTable[k];
        if ((v & 0xc0) == (0x80 | flag)) {
            // the key is on already
            Node n = s.nodes[v & 0x3f];
            info.slot = n.slot;
            n.state = ON;
            n.unlink();
            n.insertBefore(s.tail);
            return info;
        }
        if ((v & 0xc0) == (0x80 | (flag ^ 0x40))) {
            release(o, o.nodes[v & 0x3f], info);
        }
        Node n = s.oldest();
        info.slot = n.slot;
        steal(n);
        n.state = ON;
        n.unlink();
        n.insertBefore(s.tail);
        keyTable[k] = n.slot | 0x80 | flag;
        n.key = k;
        n.keyed = 1;
        return info;
    }

    /**
     * MaDva_ReleaseSlot
     * @return the slot released, + 0x40 for a wave table one, -1 none
     */
    int releaseSlot(int seq, int ch, int key) {
        int id = ch + seq * 0x10;
        Channel c = channels[id];
        int s;
        boolean isWt;
        if (c.mode == 1 || c.mode == 2) {
            int k;
            if (c.mode == 1) {
                k = id + key * 0x40;
                if ((keyTable[k] & 0x80) == 0) return -1;
            } else if (mode == 1) {
                k = id + key * 0x40;
                if ((keyTable[k] & 0x80) == 0) return -1;
                int alt = altGroups[key];
                if (alt != 0) altGroupKey[alt] = -1;
            } else {
                int alt = altGroups[key];
                int i = id + alt * 0x40;
                if (alt != 0 && altChannelKey[i] != id + key * 0x40) return -1;
                k = id + altKeys[key] * 0x40;
                if ((keyTable[k] & 0x80) == 0) return -1;
                altChannelKey[i] = -1;
            }
            int v = keyTable[k];
            keyTable[k] = v & 0x7f;
            isWt = (v & 0x40) != 0;
            s = v & 0x3f;
        } else {
            if (c.count < 1) return -1;
            if (--c.count != 0) return -1;
            s = c.slot;
            isWt = c.wt != 0;
        }
        Slots l = isWt ? wt : fm;
        Node n = l.nodes[s];
        if (n.state == ON) {
            n.state = RELEASED;
            n.unlink();
            n.insertBefore(l.mid);
        }
        return isWt ? n.slot + 0x40 : n.slot;
    }

    /** the cleaning up of a note of the channel */
    private void releaseNote(Node n, int id) {
        if (n.keyed == 1) {
            keyTable[n.key] &= 0x7f;
            if (channels[id].mode == 2) {
                int alt = altGroups[(n.key >> 6) & 0x7f];
                altGroupKey[alt] = -1;
                altChannelKey[id + alt * 0x40] = -1;
            }
        } else {
            channels[id].count = 0;
        }
    }

    /**
     * MaDva_ReleaseFmOnSlot, MaDva_ReleaseWtOnSlot: key off of all the sounding notes of the channel
     * @return the slots, bit n: slot n
     */
    long releaseOnSlots(boolean isWt, int seq, int ch) {
        Slots l = isWt ? wt : fm;
        int id = ch + seq * 0x10;
        long map = 0;
        for (Node n = l.mid.next; n != l.tail; ) {
            Node next = n.next;
            if ((n.key & 0x3f) == id) {
                releaseNote(n, id);
                if (n.state == ON) {
                    n.state = RELEASED;
                    n.unlink();
                    n.insertBefore(l.mid);
                }
                map |= 1L << n.slot;
            }
            n = next;
        }
        return map;
    }

    /**
     * MaDva_ReleaseFmAllSlot, MaDva_ReleaseWtAllSlot: damp all the notes of the channel
     * @return the slots, bit n: slot n
     */
    long releaseAllSlots(boolean isWt, int seq, int ch) {
        Slots l = isWt ? wt : fm;
        int id = ch + seq * 0x10;
        long map = 0;
        Node n = l.oldest();
        while (n != l.tail) {
            Node next = n.next;
            if (next == l.mid) next = next.next;
            if ((n.key & 0x3f) == id && n.state != DEAD) {
                releaseNote(n, id);
                n.state = DEAD;
                n.unlink();
                n.insertAfter(l.head);
                map |= 1L << n.slot;
            }
            n = next;
        }
        return map;
    }
}
