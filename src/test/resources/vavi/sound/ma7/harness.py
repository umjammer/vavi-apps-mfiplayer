from m7emu import *
import struct, collections, sys

def sym_of(m, part):
    c = [k for k in m.syms if part in k]
    assert len(c) == 1, (part, c)
    return m.syms[c[0]]

class Ma7:
    def __init__(self, fs=48000, trace=False):
        m = self.m = M7()
        self.fs = fs
        self.irq = 0
        self.intr = m.syms['_ZN14CM7_EmuSmw7App10IntHandlerEv']
        m.mu.hook_add(UC_HOOK_CODE, self._irq_hook, begin=self.intr, end=self.intr)
        self.t = Tracer(m, skip=('CString','Critical','machdep_Enter','machdep_Leave','Lock','Unlock')) if trace else None
        self.before_init()
        conf = m.malloc(0x40)
        m.mu.mem_write(conf, struct.pack('<IIBxxxIIII', 3, fs, 0, 0, 0xf, 1, 1) + struct.pack('<8H', 0x78,0x78,0x78,0x78,0xf0,0xf0,0xf0,0xf0) + struct.pack('<2HQQQ', 0xc,0xc,0x100010000c000c,0x180018,0x100000ddd))
        assert m.call('Mapi_EmuInitialize', 0, conf, 0, 0, 0, 0) == 0
        assert m.call('Mapi_Initialize') == 0
        assert m.call('Mapi_DeviceControlEx', 0x10000, 0, 0) == 0
        self.bufs = [m.malloc(4 * 4800) for i in range(8)]
        self.b7 = m.malloc(0x40)
        for i, b in enumerate(self.bufs): m.w64(self.b7 + i * 8, b)
        self.cmd = m.malloc(0x100)

    def before_init(self):
        pass

    def _irq_hook(self, mu, addr, size, ud):
        self.irq += 1

    def smw(self, name, *args):
        return self.m.call(sym_of(self.m, 'MaSmw_' + name + 'E'), *args) & 0xffffffff

    def open_rmd(self, sid=0):
        m = self.m
        self.sid = sid
        p = m.malloc(0x40)
        m.mu.mem_write(p, struct.pack('<IIQI', 0, 2, 0, 0))
        r = self.smw('Check', sid, p); print('check', hex(r))
        p = m.malloc(0x40)
        m.mu.mem_write(p, struct.pack('<IIQIxxxxQIxxxxQ', 0, 2, 0, 0, 0, 0, 0))
        r = self.smw('Open', sid, p); print('open', hex(r))
        r = self.smw('Ctrl', sid, 0x2c, 100, 0); print('vol', hex(r))  # ?
        r = self.smw('Start', sid, 0); print('start', hex(r))

    def midi(self, st, d1=0, d2=0):
        return self.smw('Ctrl', self.sid, 0x36, st | d1 << 8 | d2 << 16, self.cmd)

    def generate(self, n):
        """n samples, returns list of (l, r)"""
        m = self.m
        out = []
        while n > 0:
            k = min(n, 4800)
            r = m.call('Mapi_EmuGenerate', self.b7, k)
            assert r == 0, hex(r)
            l = struct.unpack('<%di' % k, m.mu.mem_read(self.bufs[0], 4 * k))
            rr = struct.unpack('<%di' % k, m.mu.mem_read(self.bufs[1], 4 * k))
            out.extend(zip(l, rr))
            while self.irq:
                self.irq = 0
                m.call('_Z16CBInfo_Procedurev')
            n -= k
        return out

if __name__ == '__main__':
    h = Ma7(trace=True)
    h.t.calls.clear()
    h.open_rmd()
    print(h.midi(0x90, 60, 100))
    s = h.generate(4800)
    print(max(abs(a) for a, b in s), h.irq)
    c = collections.Counter(h.t.calls)
    for n in dict.fromkeys(h.t.calls): print(c[n], n)
