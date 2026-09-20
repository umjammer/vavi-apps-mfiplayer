"""
a64j.py: translates the covered basic blocks of an arm64 function into a java method.
usage: a64j.py dis.s cov.txt method_name entry > out.java
the machine: long x0..x30, sp; vector lo/hi longs v<N>l/v<N>h; flags n z c v; memory by ld/st helpers.
"""
import re, sys

dis, covf, name, entry = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4], 16)
ins = {}
for l in open(dis):
    m = re.match(r'\s*([0-9a-f]+):\s*(\S+)\s*(.*)', l)
    if m:
        a = int(m.group(1), 16)
        raw = m.group(3)
        ops = re.sub(r'\s*<[^>]*>', '', raw)
        if m.group(2) == 'bl':
            ops = re.search(r'<(\w+)@plt>', raw).group(1)
        ops = re.sub(r'\s*//.*', '', ops).strip()
        ins[a] = (m.group(2), ops)
cov = set()
starts = set()
for l in open(covf):
    if not l.strip(): continue
    a, sz = l.split(); a = int(a, 16); sz = int(sz)
    starts.add(a)
    for x in range(a, a + sz, 4): cov.add(x)

BR = ('b', 'ret', 'cbz', 'cbnz', 'tbz', 'tbnz') 
def is_branch(mn): return mn in BR or mn.startswith('b.')
def target(mn, ops):
    return int(ops.split(',')[-1].strip(), 16)

leaders = {entry} | starts
for a in sorted(cov):
    mn, ops = ins[a]
    if is_branch(mn):
        if mn != 'ret':
            t = target(mn, ops)
            if t in cov: leaders.add(t)
        if a + 4 in cov: leaders.add(a + 4)

def R(r):
    """read a register as a java expression, w: int, x: long"""
    if r == 'wzr': return '0'
    if r == 'xzr': return '0L'
    if r == 'sp': return 'sp'
    if r[0] == 'w': return '(int) x%s' % r[1:]
    if r[0] == 'x': return 'x%s' % r[1:]
    raise ValueError(r)
def W(r, e):
    """write expression e to register"""
    if r in ('wzr', 'xzr'): return ''
    if r == 'sp': return 'sp = %s;' % e
    if r[0] == 'w': return 'x%s = (%s) & 0xffffffffL;' % (r[1:], e)
    if r[0] == 'x': return 'x%s = %s;' % (r[1:], e)
    raise ValueError(r)
def isw(r): return r[0] == 'w'

def imm(s):
    s = s.strip().lstrip('#')
    return int(s, 0)

def split_ops(ops):
    # split top level commas, keep [..] together
    out, depth, cur = [], 0, ''
    for ch in ops:
        if ch == '[': depth += 1
        if ch == ']': depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur.strip()); cur = ''
        else: cur += ch
    if cur.strip(): out.append(cur.strip())
    return out

def addr(mem):
    """returns (address expression, post-increment statement)"""
    post = ''
    m = re.match(r'\[(\w+)\](?:,\s*#(-?0x[0-9a-f]+|-?\d+))?$', mem)
    if m:
        base = R(m.group(1)) if m.group(1) != 'sp' else 'sp'
        if m.group(2):
            post = W(m.group(1), '%s + %d' % (base, int(m.group(2), 0)))
        return base, post
    m = re.match(r'\[(\w+),\s*#(-?0x[0-9a-f]+|-?\d+)\]$', mem)
    if m: return '(%s + %d)' % (R(m.group(1)), int(m.group(2), 0)), ''
    m = re.match(r'\[(\w+),\s*(w\d+),\s*uxtw\s*#(\d+)\]$', mem)
    if m: return '(%s + ((%s & 0xffffffffL) << %s))' % (R(m.group(1)), 'x' + m.group(2)[1:], m.group(3)), ''
    m = re.match(r'\[(\w+),\s*(x\d+),\s*lsl\s*#(\d+)\]$', mem)
    if m: return '(%s + (%s << %s))' % (R(m.group(1)), R(m.group(2)), m.group(3)), ''
    raise ValueError(mem)

def operand2(o):
    """the second operand of add/sub/cmp/and: imm, reg, reg shifted, reg extended"""
    o = o.strip()
    m = re.match(r'#(0x[0-9a-f]+|\d+)(?:,\s*lsl\s*#(\d+))?$', o)
    if m:
        v = int(m.group(1), 0) << (int(m.group(2)) if m.group(2) else 0)
        return str(v), True
    m = re.match(r'(\w+)(?:,\s*(lsl|lsr|asr|uxtw|sxtw)\s*#(\d+))?$', o)
    if m:
        r = m.group(1)
        if m.group(2) == 'uxtw': return '((%s & 0xffffffffL) << %s)' % ('x' + r[1:], m.group(3)), False
        if m.group(2) == 'lsl': return '(%s << %s)' % (R(r), m.group(3)), False
        if m.group(2) == 'asr': return '(%s >> %s)' % (R(r), m.group(3)), False
        if m.group(2) == 'lsr': return '(%s >>> %s)' % (R(r), m.group(3)), False
        return R(r), False
    raise ValueError(o)

def cond(c):
    return {'eq': 'z', 'ne': '!z', 'lt': 'n != v', 'ge': 'n == v', 'hi': 'c && !z', 'ls': '!c || z',
            'hs': 'c', 'lo': '!c', 'mi': 'n', 'pl': '!n', 'gt': '!z && n == v', 'le': 'z || n != v'}[c]

def flags_sub(a, b, w):
    # a - b
    if w:
        return ('{ int fa = %s, fb = (int) (%s); int fr = fa - fb; n = fr < 0; z = fr == 0; '
                'c = Integer.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; }') % (a, b)
    return ('{ long fa = %s, fb = %s; long fr = fa - fb; n = fr < 0; z = fr == 0; '
            'c = Long.compareUnsigned(fa, fb) >= 0; v = ((fa ^ fb) & (fa ^ fr)) < 0; }') % (a, b)

def vreg(r):
    return int(r[1:])

def tr(a, mn, ops):
    o = split_ops(ops)
    if mn == 'bl':
        if ops in ('memset', 'memcpy', 'memmove'):
            return '%s(x0, x1, x2);' % ops
        raise ValueError(ops)
    if mn == 'strb':
        ea, post = addr(', '.join(o[1:]))
        return 'st8(%s, %s);' % (ea, R(o[0])) + (' ' + post if post else '')
    if mn in ('ldr', 'ldrsw', 'ldrsh', 'str', 'stur', 'ldur'):
        r, (ea, post) = o[0], addr(', '.join(o[1:]))
        if mn in ('ldr', 'ldur'):
            if r[0] == 'w': s = W(r, 'ld32(%s)' % ea)
            elif r[0] == 'x': s = W(r, 'ld64(%s)' % ea)
            elif r[0] == 's': s = 'v%dl = ld32(%s) & 0xffffffffL; v%dh = 0;' % (vreg(r), ea, vreg(r))
            elif r[0] == 'd': s = 'v%dl = ld64(%s); v%dh = 0;' % (vreg(r), ea, vreg(r))
            elif r[0] == 'q': s = '{ long qa = %s; v%dl = ld64(qa); v%dh = ld64(qa + 8); }' % (ea, vreg(r), vreg(r))
        elif mn == 'ldrsw': s = W(r, '(long) ld32(%s)' % ea)
        elif mn == 'ldrsh': s = W(r, '(int) (short) ld16(%s)' % ea)
        else:
            if r in ('wzr',): s = 'st32(%s, 0);' % ea
            elif r[0] == 'w': s = 'st32(%s, %s);' % (ea, R(r))
            elif r[0] == 'x': s = 'st64(%s, %s);' % (ea, R(r))
            elif r[0] == 's': s = 'st32(%s, (int) v%dl);' % (ea, vreg(r))
            elif r[0] == 'd': s = 'st64(%s, v%dl);' % (ea, vreg(r))
            elif r[0] == 'q': s = '{ long qa = %s; st64(qa, v%dl); st64(qa + 8, v%dh); }' % (ea, vreg(r), vreg(r))
        return s + (' ' + post if post else '')
    if mn in ('ldp', 'stp'):
        r1, r2 = o[0], o[1]
        ea, post = addr(', '.join(o[2:]))
        sz = 8
        def one(r, e):
            if mn == 'ldp':
                if r[0] == 'x': return W(r, 'ld64(%s)' % e)
                if r[0] == 'd': return 'v%dl = ld64(%s); v%dh = 0;' % (vreg(r), e, vreg(r))
            else:
                if r[0] == 'x': return 'st64(%s, %s);' % (e, R(r))
                if r[0] == 'd': return 'st64(%s, v%dl);' % (e, vreg(r))
            raise ValueError(r)
        return '{ long pa = %s; %s %s }' % (ea, one(r1, 'pa'), one(r2, 'pa + 8')) + post
    if mn in ('add', 'sub', 'subs', 'adds'):
        d, s1 = o[0], o[1]
        b, isimm = operand2(', '.join(o[2:]))
        w = isw(d)
        a1 = R(s1)
        op = '+' if mn.startswith('add') else '-'
        if w: e = '%s %s (int) (%s)' % (a1, op, b)
        else: e = '%s %s %s' % (a1, op, b)
        out = ''
        if mn == 'subs': out = flags_sub(a1, b, w) + ' '
        return out + W(d, e)
    if mn == 'cmp':
        b, _ = operand2(', '.join(o[1:]))
        return flags_sub(R(o[0]), b, isw(o[0]))
    if mn == 'cmn':
        b, _ = operand2(', '.join(o[1:]))
        return flags_sub(R(o[0]), '-(%s)' % b, isw(o[0])).replace('c = Integer.compareUnsigned(fa, fb) >= 0', 'c = Long.compareUnsigned((fa & 0xffffffffL) + ((-fb) & 0xffffffffL), 0xffffffffL) > 0')
    if mn == 'and':
        b, _ = operand2(', '.join(o[2:]))
        if isw(o[0]): return W(o[0], '%s & (int) (%s)' % (R(o[1]), b))
        return W(o[0], '%s & %s' % (R(o[1]), b))
    if mn in ('lsl', 'lsr', 'asr'):
        d, s1, sh = o[0], o[1], imm(o[2])
        op = {'lsl': '<<', 'lsr': '>>>', 'asr': '>>'}[mn]
        return W(d, '%s %s %d' % (R(s1), op, sh))
    if mn == 'sbfx':
        d, s1, lsb, width = o[0], o[1], imm(o[2]), imm(o[3])
        bits = 64 if d[0] == 'x' else 32
        return W(d, '(%s << %d) >> %d' % (R(s1), bits - lsb - width, bits - width) if bits == 64 else
                    '(%s << %d) >> %d' % (R(s1), 32 - lsb - width, 32 - width))
    if mn == 'mul':
        return W(o[0], '%s * %s' % (R(o[1]), R(o[2])))
    if mn == 'smull':
        return W(o[0], '(long) %s * (long) %s' % (R(o[1]), R(o[2])))
    if mn == 'umull':
        return W(o[0], '(%s & 0xffffffffL) * (%s & 0xffffffffL)' % ('x' + o[1][1:], 'x' + o[2][1:]))
    if mn == 'sxtw':
        return W(o[0], '(long) %s' % R(o[1]))
    if mn == 'mov':
        if o[1].startswith('#'):
            return W(o[0], '%dL' % imm(o[1]) if o[0][0] == 'x' else '%d' % (imm(o[1]) & 0xffffffff if imm(o[1]) < 0x80000000 else imm(o[1]) - (1 << 32)))
        return W(o[0], R(o[1]))
    if mn == 'movk':
        m = re.match(r'#(0x[0-9a-f]+|\d+)(?:,\s*lsl\s*#(\d+))?', ', '.join(o[1:]))
        v, sh = int(m.group(1), 0), int(m.group(2) or 0)
        return W(o[0], '(%s & ~(0xffffL << %d)) | (%dL << %d)' % ('x' + o[0][1:], sh, v, sh))
    if mn == 'csel':
        return W(o[0], '(%s) ? %s : %s' % (cond(o[3]), R(o[1]), R(o[2])))
    if mn == 'fmov':
        d, s = o[0], o[1]
        if d[0] in 'sd' and s[0] in 'wx':
            if d[0] == 's': return 'v%dl = %s & 0xffffffffL; v%dh = 0;' % (vreg(d), 'x' + s[1:], vreg(d))
            return 'v%dl = %s; v%dh = 0;' % (vreg(d), R(s), vreg(d))
        if d[0] in 'wx' and s[0] in 'sd':
            if d[0] == 'w': return W(d, '(int) v%dl' % vreg(s))
            return W(d, 'v%dl' % vreg(s))
        raise ValueError(ops)
    if mn == 'sshr':
        d, s, sh = o[0], o[1], imm(o[2])
        return 'v%dl = v%dl >> %d; v%dh = 0;' % (vreg(d), vreg(s), sh, vreg(d))
    if mn == 'shl':
        d, s, sh = o[0], o[1], imm(o[2])
        dv, sv = int(d.split('.')[0][1:]), int(s.split('.')[0][1:])
        if d.endswith('.2s'):
            return ('v%dl = (((v%dl & 0xffffffffL) << %d) & 0xffffffffL) | ((v%dl >>> 32) << %d << 32); v%dh = 0;'
                    % (dv, sv, sh, sv, sh, dv))
        if d.endswith('.4s'):
            f = lambda x: '((((%s & 0xffffffffL) << %d) & 0xffffffffL) | ((%s >>> 32) << %d << 32))' % (x, sh, x, sh)
            return 'v%dl = %s; v%dh = %s;' % (dv, f('v%dl' % sv), dv, f('v%dh' % sv))
    raise ValueError('%x %s %s' % (a, mn, ops))

def br(a, mn, ops):
    """returns java for a branch at the end of a block"""
    nxt = a + 4
    def goto(t):
        return ('pc = 0x%x; break;' % t) if t in cov else ('throw new IllegalStateException("%s: 0x%x");' % (name, t))
    if mn == 'ret': return 'return;'
    if mn == 'b': return goto(target(mn, ops))
    o = split_ops(ops)
    if mn.startswith('b.'):
        c = cond(mn[2:])
    elif mn == 'cbz': c = '%s == 0' % R(o[0])
    elif mn == 'cbnz': c = '%s != 0' % R(o[0])
    elif mn == 'tbz': c = '(%s & (1L << %d)) == 0' % ('x' + o[0][1:], imm(o[1]))
    elif mn == 'tbnz': c = '(%s & (1L << %d)) != 0' % ('x' + o[0][1:], imm(o[1]))
    t = target(mn, ops)
    return 'if (%s) { %s } %s' % (c, goto(t), goto(nxt))


def goto_ret(t):
    return ('return 0x%x;' % t) if t in cov else ('throw new IllegalStateException("%s: 0x%x");' % (name, t))
def br2(a, mn, ops):
    nxt = a + 4
    if mn == 'ret': return 'return -1;'
    if mn == 'b': return goto_ret(target(mn, ops))
    o = split_ops(ops)
    if mn.startswith('b.'): c = cond(mn[2:])
    elif mn == 'cbz': c = '%s == 0' % R(o[0])
    elif mn == 'cbnz': c = '%s != 0' % R(o[0])
    elif mn == 'tbz': c = '(%s & (1L << %d)) == 0' % ('x' + o[0][1:], imm(o[1]))
    elif mn == 'tbnz': c = '(%s & (1L << %d)) != 0' % ('x' + o[0][1:], imm(o[1]))
    t = target(mn, ops)
    return 'if (%s) { %s } %s' % (c, goto_ret(t), goto_ret(nxt))

out = []
out.append('    // ---- generated by a64j.py from the executed blocks of %s' % name)
out.append('')
out.append('    private long %s;' % ', '.join('x%d' % i for i in range(31)))
out.append('    private long %s;' % ', '.join('v%dl, v%dh' % (i, i) for i in range(32)))
out.append('    private long sp;')
out.append('    private boolean n, z, c, v;')
out.append('')
out.append('    private void %s() {' % name)
out.append('        sp = STACK;')
out.append('        x0 = OBJECT;')
out.append('        int pc = 0x%x;' % entry)
out.append('        while (pc >= 0) {')
out.append('            pc = switch (pc) {')
blocks = sorted(l for l in leaders if l in cov)
for L in blocks:
    out.append('            case 0x%x -> b%x();' % (L, L))
out.append('            default -> throw new IllegalStateException("%s: pc 0x" + Integer.toHexString(pc));' % name)
out.append('            };')
out.append('        }')
out.append('    }')
for L in blocks:
    out.append('')
    out.append('    private int b%x() {' % L)
    a = L
    while True:
        mn, ops = ins[a]
        if is_branch(mn):
            out.append('        ' + br2(a, mn, ops))
            break
        out.append('        ' + tr(a, mn, ops) + ' // %x: %s %s' % (a, mn, ops))
        a += 4
        if a in leaders or a not in cov:
            out.append('        ' + goto_ret(a))
            break
    out.append('    }')
print('\n'.join(out))
