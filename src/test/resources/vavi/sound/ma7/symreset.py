import re, sys
dis = sys.argv[1]
lines = []
for l in open(dis):
    m = re.match(r'\s*([0-9a-f]+):\s*(\S+)\s*(.*)', l)
    if m:
        raw = m.group(3)
        ops = re.sub(r'\s*//.*', '', re.sub(r'\s*<[^>]*>', '', raw)).strip()
        if m.group(2) == 'bl': ops = re.search(r'<(\w+)@plt>', raw).group(1)
        lines.append((int(m.group(1), 16), m.group(2), ops))
reg = {'x0': ('this', 0), 'sp': ('sp', 0)}
writes = {}   # this offset -> byte value
stack = {}
def val(r):
    if r in ('wzr', 'xzr'): return ('c', 0)
    r = 'x' + r[1:] if r[0] == 'w' else r
    return reg.get(r, ('?', 0))
def setr(r, v):
    if r in ('wzr', 'xzr'): return
    r = 'x' + r[1:] if r[0] == 'w' else r
    reg[r] = v
def store(base, off, size, v):
    kind, b = base
    if kind == 'sp': return
    assert kind == 'this', (base, off)
    if v[0] != 'c': raise ValueError(v)
    x = v[1]
    for i in range(size):
        writes[b + off + i] = (x >> (8 * i)) & 0xff
def imm(s): return int(s.lstrip('#'), 0)
for a, mn, ops in lines:
    o = [x.strip() for x in re.split(r',(?![^\[]*\])', ops)]
    if mn in ('stp', 'str', 'strb'):
        if mn == 'stp': regs, mem = o[:2], ','.join(o[2:])
        else: regs, mem = o[:1], ','.join(o[1:])
        m = re.match(r'\[(\w+)(?:,\s*#(-?0x[0-9a-f]+|-?\d+))?\]$', mem.strip())
        base = reg['sp'] if m.group(1) == 'sp' else val(m.group(1))
        off = int(m.group(2), 0) if m.group(2) else 0
        size = 1 if mn == 'strb' else (8 if regs[0][0] == 'x' else 4)
        for k, r in enumerate(regs):
            if base[0] == 'sp':
                stack[base[1] + off + k * size] = val(r); continue
            store(base, off + k * size, size, val(r))
    elif mn == 'add':
        d, s1 = o[0], o[1]
        v = imm(o[2]) << (imm(o[3].split('#')[1]) if len(o) > 3 else 0)
        b = val(s1) if s1 != 'sp' else reg['sp']
        if d == 'sp': reg['sp'] = (b[0], b[1] + v)
        else: setr(d, (b[0], b[1] + v))
    elif mn == 'sub':
        if o[0] == 'sp':
            reg['sp'] = ('sp', reg['sp'][1] - imm(o[2])); continue
        raise ValueError(ops)
    elif mn == 'mov':
        if o[1].startswith('#'): setr(o[0], ('c', imm(o[1])))
        else: setr(o[0], val(o[1]))
    elif mn in ('ldr', 'ldp'):
        regs = o[:1] if mn == 'ldr' else o[:2]
        mem = ','.join(o[len(regs):])
        m = re.match(r'\[(\w+)(?:,\s*#(-?0x[0-9a-f]+|-?\d+))?\]$', mem.strip())
        assert m.group(1) == 'sp', ops
        off = int(m.group(2), 0) if m.group(2) else 0
        for k, r in enumerate(regs):
            setr(r, stack.get(reg['sp'][1] + off + 8 * k, ('?', 0)))
    elif mn == 'bl':
        dst, c, n = val('x0'), val('x1'), val('x2')
        assert dst[0] == 'this' and c[0] == 'c' and n[0] == 'c', (dst, c, n)
        for i in range(n[1]): writes[dst[1] + i] = c[1]
    elif mn == 'ret': break
    else: raise ValueError(mn)
print(len(writes), file=sys.stderr)
# ranges
offs = sorted(writes)
out = []
i = 0
while i < len(offs):
    s = offs[i]; j = i
    while j + 1 < len(offs) and offs[j + 1] == offs[j] + 1: j += 1
    out.append((s, offs[j] + 1)); i = j + 1
nz = [(o, v) for o, v in writes.items() if v]
print('ranges', len(out), file=sys.stderr)
for s, e in out: print('R %x %x' % (s, e))
# nonzero as 32 bit words
words = {}
for o, v in nz: words.setdefault(o & ~3, 0); words[o & ~3] |= v << (8 * (o & 3))
for o in sorted(words): print('W %x %x' % (o, words[o]))
