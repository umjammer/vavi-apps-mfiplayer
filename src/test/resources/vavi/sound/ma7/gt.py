"""
ground truth of libM7_EmuSmw7.so for the rmd (real time midi) path

usage: gt.py events.txt out_prefix [total_samples]

events.txt: lines of "<sample> <hex bytes...>", a channel message or an exclusive (f0 .. f7),
            "<sample> gen" just splits the generation there
writes out_prefix.pcm (16 bit stereo le, 48 kHz) and out_prefix.port (the port accesses of the driver:
"<sample> W <port> <data>" / "<sample> R <port> <value>")
"""
import sys, struct
from harness import *


class GT(Ma7):
    def before_init(self):
        import os
        if os.environ.get('NODSP2'):
            a = self.m.syms['_ZN3ARM5CDsp28ProcDsp2Ev']
            self.m.mu.mem_write(a, b'\xc0\x03\x5f\xd6')  # ret
        self.hook_ports()

    def hook_ports(self):
        m = self.m
        self.portlog = []
        self.now = 0
        hw = m.syms['Hw_WriteReg']; hr = m.syms['Hw_ReadReg']
        def w(mu, addr, size, ud):
            self.portlog.append('%d W %x %02x' % (self.now, mu.reg_read(UC_ARM64_REG_X0) & 0xff, mu.reg_read(UC_ARM64_REG_X1) & 0xff))
        def r(mu, addr, size, ud):
            self._rport = mu.reg_read(UC_ARM64_REG_X0) & 0xff
            lr = mu.reg_read(UC_ARM64_REG_LR)
            self._rhook = mu.hook_add(UC_HOOK_CODE, rr, begin=lr, end=lr)
        def rr(mu, addr, size, ud):
            self.portlog.append('%d R %x %02x' % (self.now, self._rport, mu.reg_read(UC_ARM64_REG_X0) & 0xff))
            mu.hook_del(self._rhook)
        for n in ('Hw_Initialize', 'Hw_Terminate'):
            a = m.syms[n]
            m.mu.hook_add(UC_HOOK_CODE, lambda mu, addr, size, ud, n=n: self.portlog.append('# %s %x' % (n, mu.reg_read(UC_ARM64_REG_X0))), begin=a, end=a)
        m.mu.hook_add(UC_HOOK_CODE, w, begin=hw, end=hw)
        m.mu.hook_add(UC_HOOK_CODE, r, begin=hr, end=hr)
        m.mu.ctl_flush_tb()

    def sysex(self, data):
        m = self.m
        p = m.malloc(len(data) + 16)
        m.mu.mem_write(p, bytes(data))
        s = m.malloc(16)
        m.w64(s, p); m.w32(s + 8, len(data))
        return self.smw('Ctrl', self.sid, 0x37, 0, s)

    def run(self, events, total, pcm):
        for t, data in events:
            if t > self.now:
                pcm.write(self.gen(t - self.now))
            if not data: pass
            elif data[0] == 0xf0:
                self.sysex(data)
            elif data:
                self.midi(data[0], data[1] if len(data) > 1 else 0, data[2] if len(data) > 2 else 0)
        if total > self.now:
            pcm.write(self.gen(total - self.now))

    def gen(self, n):
        out = bytearray()
        for l, r in self.generate(n):
            out += struct.pack('<hh', l, r)
        self.now += n
        return out


def read_events(fn):
    ev = []
    for line in open(fn):
        line = line.split('#')[0].split()
        if not line: continue
        t = int(line[0])
        if line[1:] == ['gen']: ev.append((t, b'')); continue
        ev.append((t, bytes(int(x, 16) for x in line[1:])))
    return ev


if __name__ == '__main__':
    ev = read_events(sys.argv[1])
    total = int(sys.argv[3]) if len(sys.argv) > 3 else max(t for t, d in ev) + 48000
    g = GT()
    g.portlog.append('# open')
    g.open_rmd()
    with open(sys.argv[2] + '.pcm', 'wb') as pcm:
        g.run(ev, total, pcm)
    open(sys.argv[2] + '.port', 'w').write('\n'.join(g.portlog) + '\n')
