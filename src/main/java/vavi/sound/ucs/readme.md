# vavi.sound.ucs

the fuetrek sound source (faith Type 4) in pure java

| class            | what                                                                       |
|------------------|------------------------------------------------------------------------------|
| `UcsAudioEngine` | 32 kHz, 32 voices, midi channels → preset tones or UCS waves                |
| `FuetrekVoice`   | 2 pcm/noise oscillators, tone shape filter, envelopes A/B, lfo              |
| `FuetrekRom`     | the preset tones read out of the installed `rt_synth_4.dll`                 |
| `UcsWaveBank`    | the user waves of a song, played instead of a preset tone at their (bank, program) |

the mfi synthesizer on it is [`vavi.sound.mfi.ucs`](../mfi/ucs/readme.md) and the midi spi one
[`vavi.sound.midi.ucs`](../midi/ucs/readme.md). nothing of mfi is in this package: what a song of a
phone brings besides the midi comes to the engine as the bank of a channel
(`UcsAudioEngine#bankChange`), the pitch bend halves and the master volume, and its UCS waves are
decoded into `UcsWaveBank` by [`vavi.sound.mfi.ucs`](../mfi/ucs/readme.md).

## Usage

### system properties

- `vavi.sound.faith.path` ... the authoring tool's `Tools` directory, where `rt_synth_4.dll` is (see [faith](../mfi/faith/readme.md))
- `vavi.sound.ucs.dump` ... a file what is played is written to too, raw pcm 32 kHz 16 bit stereo little endian

nothing of the dll is distributed, it is read at `open()`.

## rt_synth_4.dll

2003-08-28 build (PE time stamp `0x3f4d685e`), a sibling of DoJa 5.1 sdk's `MFiSynth_ft.dll`
(173 of the 182 samples are the same pcm)

| what                       | where / how                                                                        |
|----------------------------|------------------------------------------------------------------------------------|
| groups                     | found by structure: `0x79` melody (128), `0x78` drum (47), `0x7d` (6), `0x14` (32) |
| instrument / zone / sample | `0x08` / `0x44` / `0x24` records, pointers from the groups                         |
| pitch ratio                | `0x1000f2e4`                                                                       |
| root key tune              | `0x1000f210`                                                                       |
| mix profiles               | `0x10011030`, `0x10011438`, `0x10011840`                                           |
| note shape / drum pan      | `0x10011030` / `0x10011c48`                                                        |
| pan law                    | `0x10011cc8`                                                                       |
| control curves (12)        | `0x10011dc8` ~ `0x10012300`                                                        |
| gain / stereo curve        | `0x10012388` / `0x10012488`                                                        |
| interpolation              | `0x10012d20`                                                                       |

## notes as the native player plays them

a key struck again while it is on is not struck again, the note goes on until the last note off, as the native
player does (mfi notes longer than a gate time are notes overlapping by a tick).

## compared

`Assault_FT.mld` 60 s, a channel at a time, against openDoJa's `MLDPlayer` + `FueTrekSampler` (vavi-sound-sandbox's
test) rendered at 32 kHz without resource audio: every channel is at the same level and correlates 0.88 ~ 1.00 but one
sustained part. what is left:

* openDoJa at 48 kHz steps the envelopes by 128 output frames, 1.5 times faster than the native 32 kHz,
  long decays (bells, drums) come out quieter there
* openDoJa's player floors the frames of every event interval, it runs 30 ms ahead in a minute
* openDoJa cannot parse `Judgment_ft.mld` (unexpected EOF)

against the dll (`FaithType4Renderer`), which is fed vavi's midi and so does not get `0xe9` nor the mfi meaning of `0xe7`,
`mld_1.mld` 0.89, `Judgment_ft.mld` 0.97.

## the dll as a midi synthesizer

what `rt_synth_4.dll` does with what it is sent (`RTPSynthOpen` → `exclusive` +0x24 → `0x10004630`,
channel messages by the status nibble at `0x1000f278`), and `UcsAudioEngine` does the same

| message                        | dll                                                             |
|--------------------------------|-----------------------------------------------------------------|
| CC 0 bank select msb           | the group, latched by a program change, even groups are drums, 0 is the channel's default |
| CC 32 bank select lsb          | the sub group                                                   |
| CC 1, channel pressure         | modulation, the two added                                       |
| CC 7, 10, 11                   | volume, pan, expression                                         |
| CC 64                          | hold                                                            |
| CC 101/100 + 6/38, 96/97       | rpn 0 bend sensitivity (msb << 7, lsb added), 1 fine tuning, 2 coarse tuning, ±0x80 steps |
| CC 99/98                       | nrpn, selected but nothing is done by data entry                |
| CC 120 / 121 / 123             | all sound off / reset all controllers / all notes off (value 0 only) |
| pitch bend                     | `(bend × sensitivity) >> 4 + 8 × ((coarse + master coarse) << 13 + fine + master fine)` [Q16] |
| `f0 7e .. 09 01 f7`            | gm system on, all channels reset                                |
| `f0 7f 7f 04 01..04 ll mm f7`  | master volume, balance, fine tuning, coarse tuning              |
| any other exclusive            | nothing, **the dll has no exclusive for UCS nor for voice edit** |

the voice core runs at 32 kHz and is resampled to 44.1 kHz at the output (`0x10007af0`).

## References

* openDoJa `opendoja.audio.mld.fuetrek.FueTrekSampler` ... the behaviour of the voice (envelopes, lfo, filter, pitch) is as recovered there

## TODO

* UCS pcm is shifted to the 6 bit amplitude of the rom waves, not confirmed
* the level is 1.3 times the dll's
* working out the DLL's exclusive message format
* FuetrekVoice's arithmetic, the pitch-bend formula and the ADPCM filter coefficients follow openDoJa (GPLv3)
