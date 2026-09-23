# vavi.sound.ma7

the yamaha MA-7 in pure java, a port of
the MA-7 emulator of yamaha's android app "着信音設定" (`libM7_EmuSmw7.so`, arm64)

| class                                | what                                                                                                                      |
|--------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `Ma7AudioEngine`                     | the sound source into a line (or rendered by the caller), the bank of a song, the listener's volume, adpcm mixed in       |
| `Ma7SoundSource`                     | what the library is with a real time midi sequence open: midi in, 48 kHz stereo out                                       |
| `Ma7Driver`                          | the middleware's real time midi path (`YAMAHA::MaRmdCnv`, `MaCmd`, `MaDevDrv`): midi to packets, and the voices of a song |
| `Ma7Dva`                             | the slot allocator of the middleware (`YAMAHA::MaDva`)                                                                    |
| `Ma7Chip`                            | the chip (`Hw_*`, `ARM::`): ports, registers, 1 ms blocks                                                                 |
| `Ma7Fm`                              | 32 fm slots of 2 / 4 operators, 8 algorithms                                                                              |
| `Ma7Wt`, `Ma7Lpf`                    | 32 wave table slots (adpcm, pcm 8 / 16, noise) with their filter                                                          |
| `Ma7Interpolators`                   | the volume and the pan of a slot, stepped                                                                                 |
| `Ma7Dsp`                             | the dsp registers, the master volume (`CDsp1`)                                                                            |
| `Ma7Dsp2`                            | the effects (`CDsp2`) of the dsp program the driver writes                                                                |
| `Ma7Noise`, `Ma7Timer`, `Ma7IrqFifo` | the rest of the chip                                                                                                      |
| `Ma7Rom`                             | the rom and the tables read out of the installed `libM7_EmuSmw7.so`                                                       |

the mfi synthesizer on it is [`vavi.sound.mfi.ma7`](../mfi/ma7/readme.md), the midi spi one
[`vavi.sound.midi.ma7`](../midi/ma7/readme.md), and the smaf ones
([`vavi.sound.smaf.ma7`](../smaf/ma7/readme.md), [`vavi.sound.midi.smaf`](../midi/smaf/readme.md))
play a SMAF song on the same engine. nothing of mfi is in this package: what a song of a phone brings
besides the midi comes to the engine as the bank of a channel (`Ma7AudioEngine#bankChange`) and the
master volume of the song (`#sourceExclusive`).

## Usage

### system properties

- `vavi.sound.ma7.path` ... `libM7_EmuSmw7.so`, or the apk of the app it is in (`lib/arm64-v8a/libM7_EmuSmw7.so`
  of it), default `tmp/libM7_EmuSmw7.so`
- `vavi.sound.ma7.dump` ... a file what is played is written to too, raw pcm 48 kHz 16 bit stereo little endian
- `vavi.sound.ma7.adpcm` ... how loud the stream waves of a song are against the sound source, default 1 (level
  with it), see below

nothing of the library is distributed, it is read at `open()`. the build known is 4661808 bytes, crc32 `0x715b0baa`.

### the streams, and where the sound is cut

the MA-7 has streams of its own (4 of them, the control registers 0x5d ~ 0x71 and the fifos of the ports 6 ~ 9)
but they are not ported, so the stream waves of a song are played by the adpcm engines of vavi-sound and mixed
in by `Ma7AudioEngine#render`, the way the chip mixes anything (`CDsp1`, `Ma7Dsp#generate`):

```
bus = the sound source + the streams * vavi.sound.ma7.adpcm    ints, nothing cut
out = clamp(bus * the universal master volume)                 one volume, one clamp
```

the chip does the same with its 64 voices: they add into a bus of ints, the master volume is one multiply over
the sum, and the cut to 16 bit is at the end and happens once. so the streams are never cut twice, and the
listener's volume is of the whole song rather than the sound source alone.

it is also the room the song has: the sound source alone fills 16 bit - it was the whole output of a phone - so
a song whose streams peak with it needs about half ("GuitarMan.mmf" peaks at 32641 of 32767 at 0.5, and clips
2.4 % of its samples at 1). a song whose peaks fall apart needs less.

`vavi.sound.mobile.AudioEngine.volume` is none of this: it is the volume of the line an adpcm engine opens for
itself, and no line is opened when the streams are pulled into a song (`AudioEngineMixer`).

## How exact

it is a port of the library, not a model of it. the library is run on an arm64 emulator (unicorn,
[`m7emu.py`](../../../../../test/resources/vavi/sound/ma7/m7emu.py),
[`harness.py`](../../../../../test/resources/vavi/sound/ma7/harness.py),
[`gt.py`](../../../../../test/resources/vavi/sound/ma7/gt.py)): initialized at 48 kHz, a real time midi
sequence opened (`MaSmw_Open` type 2), midi to `MaSmw_Ctrl` 0x36 / 0x37, `Mapi_EmuGenerate` and the irq handler
between. `gt.py` writes the pcm and every port access of the driver.

* the chip, replaying the port accesses ([`Ma7Replay`](../../../../../test/java/vavi/sound/ma7/Ma7Replay.java)),
  renders the same to the bit
* the driver, playing the midi ([`Ma7DriverCompare`](../../../../../test/java/vavi/sound/ma7/Ma7DriverCompare.java)),
  writes the same bytes to the ports and so the same to the bit

for

* every gm program, 4 keys each, the drums of the set, wave table programs of all the kinds of waves
* the voices a song registers: fm of 2 and of 4 operators, wave table on a wave of the song and one of the rom,
  drum voices, and the ram filling up (`Ma7SoundSourceTest#songVoices`)
* volume, pan, expression, modulation, hold, resonance, brightness, the sends, pitch bend with its range,
  fine / coarse tuning by rpn, nrpn, bank select of 0x78, 0x79, 0x7c, 0x7d
* all sound / notes off, reset all controllers, mono / poly, poly and channel pressure (a nop packet)
* gm system on, the universal master volume, fine and coarse tuning, an exclusive not known
* 7 random runs of 2600 ~ 3300 messages on 16 channels, 12 ~ 17 s each: slots taken from notes, mono notes over fm and
  wave table voices, drums on any channel

`Ma7SoundSourceTest` keeps two of them as the crc of the library's output. 16 channels render at about 10 times
the real time.

### the effects

the library does not run a dsp program as it is: it knows programs by what they are (six parts, a variant each)
and runs code of its own for them, the coefficients going through maps of their addresses to where that code
reads them. `Ma7Dsp2` knows the one program the driver writes (`0x439870`, the variants 0, 2, 3, 9, 2, 0) and the
maps the library makes of it, and `CDsp2::ProcDsp2` of those variants is translated from the arm64 code the library
executes for them, basic block by basic block, on a memory of the same layout as the object
([`a64j.py`](../../../../../test/resources/vavi/sound/ma7/a64j.py); `CDsp2::Reset` by
[`symreset.py`](../../../../../test/resources/vavi/sound/ma7/symreset.py)). a branch into code not translated
turns the effects off. with the coefficients the driver sets on this path the effects are all but silent (1 LSB
at most, sends or not), as the library is.

## libM7_EmuSmw7.so

* `ARM::` the chip: `Hw_Initialize(fs)`, `Hw_WriteReg(port, data)`, `Hw_ReadReg(port)`, `Hw_GenerateEx`
* `YAMAHA::` the middleware (MA SMW): `MaSmw_*` the api, `MaSound`, `MaRmdCnv` the real time midi converter,
  `MaCmd_*` the commands to packets, `MaDva` the slots, `MaDevDrv` the ports
* `CM7_EmuSmw7App`, `Mapi_*` the app's api over them

### ports

| port | what                                                                                                                                |
|------|-------------------------------------------------------------------------------------------------------------------------------------|
| 0    | status, irq enable (0x80)                                                                                                           |
| 1, 2 | the intermediate registers: index, data                                                                                             |
| 3    | packets: an address of 7 bits (bit 7 the last byte), data bytes (bit 7 the last), 3 bytes of address and a count to the wave memory |
| 10   | read requests                                                                                                                       |

### control registers (the packets)

| register    | what                                                                                                                                                |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| 0           | the slot, 0 ~ 0x1f fm, 0x40 ~ 0x5f wave table                                                                                                       |
| 1 ~ 10      | the slot: the voice's address (3), pitch (2), velocity, block / fnum (2), channel, key control                                                      |
| 0x0a        | the slot's key on (0x10), off (0), damp (0x20), sound off (0x30)                                                                                    |
| 0x0b        | the channel                                                                                                                                         |
| 0x0c ~ 0x17 | the channel: 0x0c volume, 0x0d pan, 0x0e hold, 0x0f modulation, 0x10 pitch (2), 0x12 resonance, 0x13 brightness, 0x15 reverb, 0x16 chorus, 0x17 dry |
| 0x27        | nop                                                                                                                                                 |
| 0x72 ~      | the ex channels, the streams                                                                                                                        |

### driver

the channel state of a sequence at `0x64a410 + seq * 0x65d0 + channel * 0x1e`, the sequence's at `+ 0x3c0`,
the slots of `MaDva` at `0x4d87a8`

| table      | what                                                                                       |
|------------|--------------------------------------------------------------------------------------------|
| `0x42de00` | the voice of a melody program: an address, or a table of the keys (`0x42e080`, `0x42e580`) |
| `0x42df00` | the key of it, `0x42e000` fm (0) or wave table (1)                                         |
| `0x42ea80` | the voice of a drum key of a set, `0x42ec80` the key, `0x42ed80` fm or wave table          |
| `0x42ee00` | 7 bit to dB, `0x42ee80` dB to 7 bit                                                        |
| `0x42f850` | block / fnum of fm, `0x430150` of wave table, a key of a program (`0x430050`)              |
| `0x431460` | the pitch bend of a range, `0x42ef50` fine tune, `0x42f750` coarse tune                    |
| `0x38c250` | the exclusive groups of the drums, `0x38c2d0` the key sharing a slot                       |
| `0x439870` | the dsp program the driver writes, `0x439270` the coefficients                             |

### the library as it is

what a port of it has to do, and this does

* a new wave table note of a channel which was mono with an fm note sounding checks the wave table slot of the
  fm slot's number, and puts it on the list of the fm slots (`MaDva_GetWtSlot`)
* mono mode is not taken by a drum channel
* a pitch bend takes only the msb
* an unknown controller, nrpn data entry, pressure makes a nop packet

### the voices of a song

a song of a phone brings voices of its own, which its notes sound instead of the ones of the rom, and `Ma7Driver`
takes them the way the library's real time midi path does (`MaRmdCnv_SetLongMsg` of the MA-3 driver, `marmdcnv.c`,
is the same code):

| message                           | what                                                                                                  |
|-----------------------------------|--------------------------------------------------------------------------------------------------------|
| `f0 43 79 06 7f 01 mm ll pc dn vt <voice> f7` | a voice, its data packed 7 bit: `mm` 0x7c a melody voice of the bank `ll` and the program `pc`, 0x7d a drum one of the kit `pc` and the key `dn`; `vt` 0 fm (17 or 31 bytes, by the algorithm), 1 wave table (16) |
| `f0 43 79 06 7f 03 id fl <wave> f7`           | the wave a wave table voice plays, packed 7 bit as well                                  |

the voice goes into the chip's ram (`MaDevDrv_SendDirectRamData`, 16 KB after the 64 KB of the wave rom) in the
chip's own layout, which is the song's with the 5th bits of an operator's rates and its fixed pitch (none of a
song's) added and the multiplier taken through the chip's table (11, 13, 14 are none of its); a wave table voice
gets the address of its wave, one of the song's or of the rom (the id's bit 7). `MaCmd_SetMelody` / `SetDrum` then
point the bank and the program at it, and a note finds it by `MaCmd_GetVoiceInfo` before the rom's tables. a bank
and a program which have a voice already keep it, and so does the ram once it is full.

## References

* https://github.com/umjammer/vavi-sound-sion
* https://github.com/umjammer/vavi-sound-ma
* https://github.com/dlawoals2713/MMF-Player/blob/master/app/src/main/java/com/yamaha/smafsynth/m7/emu/EmuSmw7.java
* https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool
* https://github.com/akustikrausch/yamaha-smaf-player

## TODO

* dsp programs other than the driver's (SMAF's), the dsp's control registers 0x7a ~ 0x7f, the eq of `CDsp1`
* the adpcm and the streams of the MA-7 itself
* the voice messages of the MA-7 itself (`f0 43 79 08 7f 21 ...`) and the filter ("AL") a song may send before a
  voice, which the real time midi path of the library does not take either (`MaMmfCnv` does, playing a file)
* the fm user waves (`FMCONTROL_SetFMWaveReg`), no voice of the rom takes them
