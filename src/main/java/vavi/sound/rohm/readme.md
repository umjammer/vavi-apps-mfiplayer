# vavi.sound.rohm

the rohm sound source (faith Type 2, BU8788KN / BU8709KN) in pure java

| class             | what                                                                                                          |
|-------------------|---------------------------------------------------------------------------------------------------------------|
| `RohmAudioEngine` | the sound source into a line (or rendered by the caller), the bank of a song, the listener's volume, adpcm mixed in |
| `RohmSoundSource` | what `rt_synth_2.dll` is: midi in, 128 frames of 44.1 kHz stereo out                                          |
| `RohmDriver`      | notes to voices: programs, layers, zones, voice allocation, the controllers                                   |
| `RohmLsi`         | the 64 voices of the chip: wave, filter, envelopes, lfo, mix                                                  |
| `RohmReverb`      | the 8 reverb presets after the voices                                                                         |
| `RohmRom`         | the rom read out of the installed `rt_synth_2.dll`                                                            |

the mfi synthesizer on it is [`vavi.sound.mfi.rohm`](../mfi/rohm/readme.md) and the midi spi one
[`vavi.sound.midi.rohm`](../midi/rohm/readme.md). nothing of mfi is in this package: what a song of a
phone brings besides the midi comes to the engine as the bank of a channel (`RohmAudioEngine#bankChange`)
and the master volume of the song (`#sourceExclusive`).

## Usage

### system properties

- `vavi.sound.faith.path` ... the authoring tool's `Tools` directory, where `rt_synth_2.dll` is (see [faith](../mfi/faith/readme.md))
- `vavi.sound.rohm.dump` ... a file what is played is written to too, raw pcm 44.1 kHz 16 bit stereo little endian

nothing of the dll is distributed, it is read at `open()`.

## How exact

it is a port of the dll, not a model of it: the same integer arithmetic, 16 bit where the dll keeps 16 bits,
the same order of things in a block. what it renders is compared with what the dll renders for the same
messages (the dll run by [`rts2r.c`](../../../../../test/resources/vavi/sound/rohm/rts2r.c) on wine),
and it is the same to the bit for

* every melody program, keys 0 ~ 127
* the drum set 0x78, the second set 0x14, the drum program 0x19, the groups 0x7d and 0x11 and the UCS ones without UCS
* volume, pan, expression, modulation, pitch bend with sensitivity, hold, all notes / sound off, reset all controllers
* 300 notes on 64 voices (voices taken from notes)
* gm system on, the universal master volume, balance, fine and coarse tuning
* the 8 reverb presets
* UCS waves
* 6 songs of `F901iC` converted by vavi, 20 s each

`RohmSoundSourceTest` keeps two of them as the crc of the dll's output. 64 voices sounding render at 70 times
the real time.

## rt_synth_2.dll

2003-08-28 build (PE time stamp `0x3f4d683f`), "Ring Tone Player Synth Simulator Type 2", `.data` at `0x10017000`

### api

three exports as `rt_synth_4.dll`, but `RTPSynthOpen` hands back the synthesizer itself (not a pair of it and a
voice set), and the synthesizer has an entry more

| offset | what                                                                                                 |
|--------|------------------------------------------------------------------------------------------------------|
| 0x04   | 64 voices                                                                                            |
| 0x08   | 1 slot                                                                                               |
| 0x10   | render: 128 frames, 32 bit ints of 16 bit range, written, not added                                  |
| 0x18   | open a slot: all voices off, the master volume, balance and tunings to their defaults                |
| 0x20   | channel messages, 4 bytes each, queued and taken at the next render                                  |
| 0x24   | exclusive: gm system on (device 0x7f only), universal master volume, balance, fine and coarse tuning |
| 0x28   | UCS: `0x10001` pcm into the wave ram, `0x10002` wave headers (big endian words), `0x10003` zones     |
| 0x2c   | the reverb preset 0 ~ 7, off until this or a gm system on (preset 0, which is dry)                   |

### memory

| what        | where        |                                                                                                         |
|-------------|--------------|---------------------------------------------------------------------------------------------------------|
| wave        | `0x100172d0` | 256 * 12: address / 4, loop start, end, pad, pitch (u32), 192 ~ 201 for UCS                             |
| zone        | `0x10017ed0` | 640 * 32, a key split of a layer, 576 ~ 583 for UCS                                                     |
| program     | `0x1001ced0` | 256 * 6: key, pan, 2 layers of exclusive group (4) and the first zone (12), patched when the dll starts |
| wave memory | `0x1001d4d0` | 0x20000 of signed 8 bit pcm, 0x2000 of ram for UCS                                                      |
| drum pans   | `0x1003f4d0` | ascii - 0x40 of the programs 128 ~ 174 and 207 ~ 221                                                    |
| drum groups | `0x1003f510` | the exclusive groups of the same                                                                        |
| level       | `0x1003f5d0` | 7 bit to 15 bit                                                                                         |
| pan         | `0x1003f6d0` | sine law, left is [128 - pan]                                                                           |
| lfo depths  | `0x1003f7d8` | pitch up / down, amplitude and filter up / down                                                         |
| lfo rate    | `0x1003fbd8` |                                                                                                         |
| eg level    | `0x1003fcd8` | Q13, 0x80 is 1, of the filter and the pitch                                                             |
| time        | `0x1003fee0` | blocks of the first stage of an envelope, the lfo delay                                                 |
| rate        | `0x1003ffe0` | Q15, how near a block goes to the target                                                                |
| cutoff      | `0x100401c0` | resonance * 97 + cutoff (0x10 ~ 0x70)                                                                   |
| resonance   | `0x10040de0` | the damping, the same index                                                                             |
| pitch       | `0x10041a20` | 768, 2^(i / 768) * 0x4000                                                                               |
| reverb      | `0x100170e0` | 8 * 0x38: time (double), 8 delays, decimation, dry %, wet %                                             |

### program

| program   | what                                                  | midi                                 |
|-----------|-------------------------------------------------------|--------------------------------------|
| 0 ~ 127   | the melody group, a layer                             | bank select msb 0x79                 |
| 128 ~ 174 | the drums, key 35 ~ 81, hi-hats in an exclusive group | an even bank select msb (0x78)       |
| 175 ~ 206 | the second drum set, key 35 ~ 66                      | 0x14                                 |
| 207 ~ 221 | the drum program 0x19, key 35, 36, 38 ~ 50            | drum program 0x19                    |
| 222 ~ 227 | the group 0x7d, 6 tones                               | 0x7d                                 |
| 228 ~ 235 | UCS                                                   | 0x11 program 0 ~ 7, a drum key 0 ~ 6 |

a program sounds from its key up (the melody from key 9), a layer sounds a zone (a voice) for a key. a
zone of no UCS walks on into the program table, and plays what is there, as the dll does.

### zone

| offset | what                           | offset | what                                                  |
|--------|--------------------------------|--------|-------------------------------------------------------|
| 0x00   | key high, 0x80 the last        | 0x10   | pitch eg attack level                                 |
| 0x01   | wave                           | 0x11   | cutoff                                                |
| 0x02   | filter key follow point        | 0x12   | filter eg attack time                                 |
| 0x03   | fine tune, 1/64 semitone       | 0x13   | filter eg decay time                                  |
| 0x04   | coarse tune                    | 0x14   | filter eg release time                                |
| 0x05   | lfo rate                       | 0x15   | filter eg initial level                               |
| 0x06   | lfo pitch depth (+ modulation) | 0x16   | filter eg sustain level                               |
| 0x07   | lfo filter depth               | 0x17   | filter eg release level                               |
| 0x08   | lfo amplitude depth            | 0x18   | filter eg attack level                                |
| 0x09   | lfo delay                      | 0x19   | amplitude attack time and rate                        |
| 0x0a   | pitch eg attack time           | 0x1a   | amplitude decay rate                                  |
| 0x0b   | pitch eg decay time            | 0x1b   | amplitude release rate                                |
| 0x0c   | pitch eg release time          | 0x1c   | key follow (2), amplitude initial level (6)           |
| 0x0d   | pitch eg initial level         | 0x1d   | amplitude sustain level                               |
| 0x0e   | pitch eg sustain level         | 0x1e   | level                                                 |
| 0x0f   | pitch eg release level         | 0x1f   | resonance (4), filter key follow (2), filter type (2) |

what "UCS Editor R" edits (`UCSEditorRManual_E.pdf`): the tune, the amplitude, filter and pitch envelopes, the
filter (thru / low / high / band pass, key follow +1 / -1 / -0.5 / -0.25), the lfo.

### voice

* the wave: linear interpolation of 9 bits, 16.16 position, a loop
* a state variable filter (Chamberlin), its cutoff by the filter eg and the lfo, the damping by the resonance
* 3 envelopes of a block (128 samples): a first stage for the time of it, then a second, then the release,
  each a step to the target by the rate; the amplitude ramped over the block
* a triangle lfo after a delay, to the pitch, the filter and the amplitude
* mixed by a pan law, the pans moving 1/32 a block to where they are told
* the mix `>> 5`, then `>> 7` (`>> 6` before a slot is opened), cut to 16 bits

### the dll as it is

what a port of it has to do, and this does

* what is sent is taken at the end of the block after the next, 2 blocks late
* a volume change takes the pans a step to the left (`[127 - pan]`), a pan change takes the velocity as it is rather than as a level
* a pitch bend of a drum takes its midi key rather than the key the drum is played at, and a zone following
  a quarter of the key gets its coarse tune twice
* reset all controllers sets the pan so that a master balance after it puts the channel hard right, and the program of the channel to 0
* a note off of a drum is not taken, but for the long whistle and the long guiro
* the attack of the amplitude clears the key on of the voice, which only the driver's bookkeeping sees

## TODO

* UCS: the dll's own is `RohmSoundSource#ucsPcm`, `#ucsWaves`, `#ucsZones`
* the level against the other synthesizers
