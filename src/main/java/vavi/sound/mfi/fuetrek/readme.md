# vavi.sound.mfi.fuetrek

an mfi synthesizer that is the fuetrek sound source (faith Type 4, docomo UCS) in pure java: the sound
source itself is [`vavi.sound.fuetrek`](../../fuetrek/readme.md)

| class                   | what                                                                                                                                                    |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FuetrekMfiSynthesizer` | mfi synthesizer, receiver only: channel messages and the mfi values to the engine, exclusives (UCS waves, adpcm) to `VaviMfiSynthesizer#processSpecial` |
| `UcsFunction`           | the UCS machine dependent functions, the same whichever vendor sends them                                                                               |
| `UcsSequencer`          | the UCS messages of a song (`0x10` ~ `0x12`) decoded into the wave bank of the sound source (`UcsWaveBank`)                                             |

the sound source knows nothing of mfi: `FuetrekMfiReceiver` and `UcsSequencer` are the whole of it here.

## UCS messages

vendor/carrier `0x71` (sharp), `0x41` (panasonic, P905i, P705i use fuetrek too, not confirmed by a sample yet) and
`0x01` (no vendor: the carrier wide `MFi5PlugIn_DoCoMo`, `vers` 0500, vavi-sound's `vavi.sound.mfi.vavi.mfi5`)

| function       | contents                                                                                                                                                                     |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0x10`         | wave `#`, type 1: length(3) loopStart(3) loopEnd(3) / type 2: length(2) signed 8 bit pcm                                                                                     |
| `0x11`         | wave `#`, `0x02`, length `0x2c`, the 44 byte voice parameters of the sound source (the "voice edit" control), `[0]` bit 0: uploaded wave, `[6]` root key, `[7]` encoded tune |
| `0x12`         | wave `#`, `0x00`, length `0x04`, `0x80` drum bank program                                                                                                                    |
| `0xb0`, `0xb1` | ?                                                                                                                                                                            |

* a wave is played by the notes of the mfi (bank, program) `0x12` assigns it to, a drum one (drum bit 0) by the note
  `program` (the midi key - 35) of a percussion channel of the bank, at its root key
* the tune of `[7]` is the dll's root key tune table, `Judgment_ft.mld` wave 1 comes out at key 67.07 for 67

### the voice parameters a part at a time (`0x01`)

the MFi 5 writer writes `0x11` with other parameter numbers than `0x02` too, each is the same bytes of the 44 byte
record (read from the 191 files of it in the corpus), `UcsWaveBank.Wave#setParameters` writes them over the record
and keeps which bytes are written, `FuetrekVoice.Template#edit` takes only those

| parameter | record      | what                                                                                  |
|-----------|-------------|---------------------------------------------------------------------------------------|
| `0x02`    | `[0]~[43]`  | the whole                                                                             |
| `0x10`    | `[0]~[5]`   | flags, wave, the preset derived from: with `[0]` bit 0 clear, a voice of a preset tone |
| `0x20`    | `[8]`       | link, the voice of oscillator B, always the next one                                  |
| `0x21`    | `[9]`       | oscillator balance                                                                    |
| `0x40`    | `[12]~[18]` | env A, written while the song plays too                                               |
| `0x52`    | `[27]~[28]` | shape w4, in the range of `[27]` of the whole records                                 |

* **a voice of a preset tone** (22 in the corpus) has no wave: it is the rom zone of the melody (bank, program) of
  `[3]`, `[4]`, the parts written going over the zone's parameters. what `[5]` is, is not known
* **a pair**: a voice whose `[9]` is not 0 has the voice of `[8]` as oscillator B, 30 of 30 of those are the next voice
  with `[0]` bit 1 set, which has no `0x12` of its own (53 of 53). the `[8]` of a voice whose `[9]` is 0 says nothing
  (241 of them name a voice that is not a second one)
* the other parameter numbers, if any, are let go

## mfi values midi has no room for

vavi-sound sends them as `f0 45 04 sub ... f7` (`MfiValueExclusive`) next to the midi messages it
converts as before, the other synthesizers let the exclusive go

| sub | mfi    | data          | `UcsAudioEngine`                                                                              |
|-----|--------|---------------|-----------------------------------------------------------------------------------------------|
| 01  | `0xe1` | channel bank  | the mfi bank selector below                                                                   |
| 02  | `0xb0` | volume        | the song's master volume, the universal master volume following is not the listener's         |
| 03  | `0xe9` | channel fine  | the low half of the pitch bend, `(((0xe4 << 5) + 0xe9) << 3) - 0x100`                         |
| 04  | `0xe7` | channel value | a modulation lane (value × 2) as the native player takes it, the rpn 0 following is not taken |

| bank     | melody channel                                  | drum channel (9)         |
|----------|-------------------------------------------------|--------------------------|
| not told | the group of bank select msb, `0x79` by default | `0x78` by note           |
| 0        | `0x7d` (mfi 1 square/sine, 0 ~ 5)               | `0x78`                   |
| 1 ~ 0x33 | `0x79`, odd banks + 0x40                        | `0x78`                   |
| 0x34     | -                                               | `0x14` by note (35 ~ 66) |
| 0x36     | `0x11` → `0x79`                                 | `0x10` → `0x78`          |

## TODO

* UCS against the dll: the dll has no exclusive for it, how the authoring tool gives it a UCS voice is not known (`param` +0x28?)
* mfi `0xe8`: the native player commits the low half of the pitch bend by it, a corpus analysis says it is not a part of the pitch bend (`nec/readme.md`), not sent
* `0xba` (channel configuration: drum family and pan mode of the native player)
* `0xb0`, `0xb1` of the UCS messages, and `0x40` of `0x01` (`vavi.sound.mfi.vavi.mfi5.Function64`)
* the other parameter numbers of `0x11` (the numbering looks like `0x1#` [0] ~, `0x2#` [8] ~, `0x4#` [12] ~, `0x5#`
  [19] ~ but only the ones above are in the corpus), a preset drum voice
* a drum wave at its root key is a guess: the root key of a drum wave is not the key it is struck by (107 of 107)
