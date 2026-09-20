"""
unicorn loader for libM7_EmuSmw7.so (android arm64), single threaded, deterministic.
"""
import os, struct, sys
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import *
from unicorn.arm64_const import *

SO = os.environ.get('M7_SO', 'tmp/libM7_EmuSmw7.so')
BASE = 0x10000000
STUB = 0x08000000          # import stubs, 16 bytes each
HEAP = 0x20000000
HEAP_SIZE = 0x04000000
STACK = 0x30000000
STACK_SIZE = 0x00200000
DATA = 0x07000000          # imported data (__sF, __stack_chk_guard)
RET = 0x06000000           # magic return address


class M7:
    def __init__(self, trace_imports=False):
        self.mu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.trace_imports = trace_imports
        self.syms = {}
        self.imports = {}   # stub addr -> name
        self.heap_ptr = HEAP
        self.threads = []
        self.log = []
        self._load()

    # ---- memory helpers
    def r8(self, a): return self.mu.mem_read(a, 1)[0]
    def r16(self, a): return struct.unpack('<H', self.mu.mem_read(a, 2))[0]
    def r32(self, a): return struct.unpack('<I', self.mu.mem_read(a, 4))[0]
    def r64(self, a): return struct.unpack('<Q', self.mu.mem_read(a, 8))[0]
    def w8(self, a, v): self.mu.mem_write(a, bytes([v & 0xff]))
    def w32(self, a, v): self.mu.mem_write(a, struct.pack('<I', v & 0xffffffff))
    def w64(self, a, v): self.mu.mem_write(a, struct.pack('<Q', v & 0xffffffffffffffff))
    def rstr(self, a):
        b = bytearray()
        while True:
            c = self.r8(a); a += 1
            if c == 0: return bytes(b)
            b.append(c)

    def malloc(self, n):
        n = (n + 15) & ~15
        p = self.heap_ptr
        self.heap_ptr += max(n, 16)
        if self.heap_ptr > HEAP + HEAP_SIZE: raise MemoryError('heap')
        self.mu.mem_write(p, b'\0' * n)
        return p

    def _load(self):
        mu = self.mu
        f = open(SO, 'rb')
        elf = ELFFile(f)
        hi = 0
        for seg in elf.iter_segments():
            if seg['p_type'] == 'PT_LOAD':
                hi = max(hi, seg['p_vaddr'] + seg['p_memsz'])
        size = (hi + 0xffff) & ~0xffff
        mu.mem_map(BASE, size)
        for seg in elf.iter_segments():
            if seg['p_type'] == 'PT_LOAD':
                mu.mem_write(BASE + seg['p_vaddr'], seg.data())
        mu.mem_map(STUB, 0x10000)
        mu.mem_map(HEAP, HEAP_SIZE)
        mu.mem_map(STACK, STACK_SIZE)
        mu.mem_map(DATA, 0x10000)
        mu.mem_map(RET, 0x1000)
        mu.mem_write(RET, b'\x00\x00\x00\x14' * 4)  # b . (never reached, we stop at RET)
        dynsym = elf.get_section_by_name('.dynsym')
        for s in dynsym.iter_symbols():
            if s['st_shndx'] != 'SHN_UNDEF' and s['st_value']:
                self.syms[s.name] = BASE + s['st_value']
        n = 0
        data_ptr = DATA
        for sec in elf.iter_sections():
            if not isinstance(sec, RelocationSection): continue
            for r in sec.iter_relocations():
                t = r['r_info_type']
                off = BASE + r['r_offset']
                if t == 1027:  # RELATIVE
                    mu.mem_write(off, struct.pack('<Q', BASE + r['r_addend']))
                elif t in (1025, 1026):  # GLOB_DAT, JUMP_SLOT
                    name = dynsym.get_symbol(r['r_info_sym']).name
                    if name in ('__sF', '__stack_chk_guard'):
                        mu.mem_write(off, struct.pack('<Q', data_ptr))
                        mu.mem_write(data_ptr, struct.pack('<Q', 0x5a5a5a5a12345678))
                        data_ptr += 0x400
                    else:
                        a = STUB + n * 16
                        n += 1
                        self.imports[a] = name
                        mu.mem_write(a, b'\xc0\x03\x5f\xd6')  # ret
                        mu.mem_write(off, struct.pack('<Q', a))
                else:
                    raise ValueError('reloc %d' % t)
        mu.hook_add(UC_HOOK_CODE, self._import_hook, begin=STUB, end=STUB + 0x10000)
        mu.hook_add(UC_HOOK_MEM_UNMAPPED, self._fault)
        # init array
        ia = elf.get_section_by_name('.init_array')
        for i in range(ia['sh_size'] // 8):
            fn = self.r64(BASE + ia['sh_addr'] + i * 8)
            if fn and fn != 0xffffffffffffffff:
                self.call(fn)

    def _fault(self, mu, access, addr, size, value, ud):
        pc = mu.reg_read(UC_ARM64_REG_PC)
        print('fault access=%d addr=%x pc=%x (%x)' % (access, addr, pc, pc - BASE), file=sys.stderr)
        return False

    def arg(self, i):
        return self.mu.reg_read(UC_ARM64_REG_X0 + i)

    def _import_hook(self, mu, addr, size, ud):
        name = self.imports.get(addr)
        if name is None: return
        x = [mu.reg_read(UC_ARM64_REG_X0 + i) for i in range(8)]
        ret = 0
        if self.trace_imports: print('import', name, [hex(v) for v in x[:4]], file=sys.stderr)
        if name == 'malloc': ret = self.malloc(x[0])
        elif name == 'free': ret = 0
        elif name == 'memcpy' or name == 'memmove':
            if x[2]: mu.mem_write(x[0], bytes(mu.mem_read(x[1], x[2])))
            ret = x[0]
        elif name == 'memset':
            if x[2]: mu.mem_write(x[0], bytes([x[1] & 0xff]) * x[2])
            ret = x[0]
        elif name == 'memcmp':
            a = bytes(mu.mem_read(x[0], x[2])); b = bytes(mu.mem_read(x[1], x[2]))
            ret = 0 if a == b else (1 if a > b else 0xffffffffffffffff)
        elif name == 'strlen': ret = len(self.rstr(x[0]))
        elif name == 'strcmp':
            a = self.rstr(x[0]); b = self.rstr(x[1])
            ret = 0 if a == b else (1 if a > b else 0xffffffffffffffff)
        elif name == 'strcpy':
            s = self.rstr(x[1]); mu.mem_write(x[0], s + b'\0'); ret = x[0]
        elif name == 'strncpy':
            s = self.rstr(x[1])[:x[2]]; mu.mem_write(x[0], s + b'\0' * (x[2] - len(s))); ret = x[0]
        elif name == 'strstr':
            a = self.rstr(x[0]); b = self.rstr(x[1]); i = a.find(b); ret = 0 if i < 0 else x[0] + i
        elif name == 'tolower': ret = ord(chr(x[0] & 0xff).lower()) if x[0] < 128 else x[0]
        elif name == 'isspace': ret = 1 if chr(x[0] & 0xff) in ' \t\n\r\v\f' else 0
        elif name == 'pthread_create':
            self.threads.append((x[2], x[3]))
            self.w64(x[0], 0x1000 + len(self.threads))
            ret = 0
        elif name == 'pthread_cond_timedwait': ret = 110  # ETIMEDOUT
        elif name == 'pthread_key_create':
            self.w32(x[0], 1); ret = 0
        elif name == 'pthread_getspecific': ret = getattr(self, '_tls', 0)
        elif name == 'pthread_setspecific': self._tls = x[1]; ret = 0
        elif name.startswith('pthread_') or name in ('sched_yield', 'nanosleep', '__cxa_atexit', '__cxa_finalize'):
            ret = 0
        elif name == 'gettimeofday':
            if x[0]: mu.mem_write(x[0], struct.pack('<qq', 0, 0))
            ret = 0
        elif name == 'clock_gettime':
            if x[1]: mu.mem_write(x[1], struct.pack('<qq', 0, 0))
            ret = 0
        elif name in ('dlopen', 'dlsym', 'dlclose', 'dlerror', 'dl_iterate_phdr'): ret = 0
        elif name in ('fprintf', 'vsprintf'):
            ret = 0
            if name == 'vsprintf': self.w8(x[0], 0)
        elif name == 'mmap': ret = self.malloc(x[1])
        elif name == 'munmap': ret = 0
        elif name in ('abort', '__assert2', '__stack_chk_fail'):
            raise RuntimeError('%s lr=%x' % (name, mu.reg_read(UC_ARM64_REG_LR) - BASE))
        else:
            raise RuntimeError('import ' + name)
        mu.reg_write(UC_ARM64_REG_X0, ret & 0xffffffffffffffff)

    def call(self, fn, *args, count=0):
        """calls fn (absolute address or symbol name), up to 8 int args"""
        mu = self.mu
        if isinstance(fn, str): fn = self.syms[fn]
        sp = mu.reg_read(UC_ARM64_REG_SP)
        if sp == 0 or not (STACK <= sp <= STACK + STACK_SIZE):
            sp = STACK + STACK_SIZE - 0x100
        for i, a in enumerate(args):
            mu.reg_write(UC_ARM64_REG_X0 + i, a & 0xffffffffffffffff)
        mu.reg_write(UC_ARM64_REG_SP, sp - 0x100 & ~15)
        mu.reg_write(UC_ARM64_REG_LR, RET)
        mu.emu_start(fn, RET, count=count)
        mu.reg_write(UC_ARM64_REG_SP, sp)
        return mu.reg_read(UC_ARM64_REG_X0)

    def sym(self, name):
        return self.syms[name]


if __name__ == '__main__':
    m = M7(trace_imports=True)
    print(len(m.syms), 'symbols', len(m.imports), 'imports', m.threads)


def demangle_all(names):
    import subprocess
    out = subprocess.run(['/opt/homebrew/opt/llvm/bin/llvm-cxxfilt'], input='\n'.join(names), capture_output=True, text=True).stdout.split('\n')
    return dict(zip(names, out))


class Tracer:
    def __init__(self, m, skip=()):
        self.m = m
        self.names = {}
        dm = demangle_all(list(m.syms.keys()))
        for k, v in m.syms.items():
            n = dm.get(k, k)
            n = n.split('(')[0]
            self.names.setdefault(v, n)
        self.skip = skip
        self.calls = []
        self.h = m.mu.hook_add(UC_HOOK_BLOCK, self._hook, begin=BASE, end=BASE + 0x200000)
    def _hook(self, mu, addr, size, ud):
        n = self.names.get(addr)
        if n and not any(s in n for s in self.skip):
            self.calls.append(n)
    def stop(self):
        self.m.mu.hook_del(self.h)
