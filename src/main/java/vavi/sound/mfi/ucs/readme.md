# vavi.sound.mfi.ucs

the fuetrek sound source (faith Type 4, docomo UCS) in pure java

| class            | what                                                                                                                              |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `UcsSynthesizer` | mfi synthesizer, receiver only: channel messages to the engine, exclusives (UCS waves, adpcm) to `VaviSynthesizer#processSpecial` |
| `UcsAudioEngine` | 32 kHz, 32 voices, midi channels → preset tones or UCS waves                                                                      |
| `FuetrekVoice`   | 2 pcm/noise oscillators, tone shape filter, envelopes A/B, lfo                                                                    |
| `FuetrekRom`     | the preset tones read out of the installed `rt_synth_4.dll`                                                                       |
| `UcsSequencer`   | the UCS waves of a file (`0x10` ~ `0x12`)                                                                                         |

## Usage

### system properties

- `vavi.sound.mfi.faith.path` ... the authoring tool's `Tools` directory, where `rt_synth_4.dll` is (see [faith](../faith/readme.md))

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

## UCS messages

vendor/carrier `0x71` (sharp) and `0x41` (panasonic, P905i, P705i use fuetrek too, not confirmed by a sample yet)

| function       | contents                                                                                                                                                                     |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0x10`         | wave `#`, type 1: length(3) loopStart(3) loopEnd(3) / type 2: length(2) signed 8 bit pcm                                                                                     |
| `0x11`         | wave `#`, `0x02`, length `0x2c`, the 44 byte voice parameters of the sound source (the "voice edit" control), `[0]` bit 0: uploaded wave, `[6]` root key, `[7]` encoded tune |
| `0x12`         | wave `#`, `0x00`, length `0x04`, `0x80 0x00` bank program                                                                                                                    |
| `0xb0`, `0xb1` | ?                                                                                                                                                                            |

* a wave is played by the notes of the mfi (bank, program) `0x12` assigns it to
* the tune of `[7]` is the dll's root key tune table, `Judgment_ft.mld` wave 1 comes out at key 67.07 for 67

## mfi values midi has no room for

vavi-sound sends them as `f0 45 04 sub ... f7` (`MfiSoundSourceExclusive`) next to the midi messages it
converts as before, the other synthesizers let the exclusive go

| sub | mfi    | data          | `UcsAudioEngine`                                                       |
|-----|--------|---------------|------------------------------------------------------------------------|
| 01  | `0xe1` | channel bank  | the mfi bank selector below                                            |
| 02  | `0xb0` | volume        | the song's master volume, the universal master volume following is not the listener's |
| 03  | `0xe9` | channel fine  | the low half of the pitch bend, `(((0xe4 << 5) + 0xe9) << 3) - 0x100` |
| 04  | `0xe7` | channel value | a modulation lane (value × 2) as the native player takes it, the rpn 0 following is not taken |

| bank          | melody channel            | drum channel (9)   |
|---------------|---------------------------|--------------------|
| not told      | the group of bank select msb, `0x79` by default | `0x78` by note |
| 0             | `0x7d` (mfi 1 square/sine, 0 ~ 5) | `0x78`     |
| 1 ~ 0x33      | `0x79`, odd banks + 0x40  | `0x78`             |
| 0x34          | -                         | `0x14` by note (35 ~ 66) |
| 0x36          | `0x11` → `0x79`           | `0x10` → `0x78`    |

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

* UCS against the dll: the dll has no exclusive for it, how the authoring tool gives it a UCS voice is not known (`param` +0x28?)
* UCS pcm is shifted to the 6 bit amplitude of the rom waves, not confirmed
* the level is 1.3 times the dll's
* `0xb0`, `0xb1`
* mfi `0xe8`: the native player commits the low half of the pitch bend by it, a corpus analysis says it is not a part of the pitch bend (`nec/readme.md`), not sent
* `0xba` (channel configuration: drum family and pan mode of the native player)
* working out the DLL's exclusive message format
