# vavi.sound.mfi.ucs

an mfi synthesizer that is the fuetrek sound source (faith Type 4, docomo UCS) in pure java: the sound
source itself is [`vavi.sound.ucs`](../../ucs/readme.md)

| class                | what                                                                                                                        |
|----------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `UcsMfiSynthesizer`  | mfi synthesizer, receiver only: channel messages and the mfi values to the engine, exclusives (UCS waves, adpcm) to `VaviMfiSynthesizer#processSpecial` |
| `UcsFunction`        | the UCS machine dependent functions, the same whichever vendor sends them                                                    |
| `UcsSequencer`       | the UCS messages of a song (`0x10` ~ `0x12`) decoded into the wave bank of the sound source (`UcsWaveBank`)                  |

the sound source knows nothing of mfi: `UcsMfiReceiver` and `UcsSequencer` are the whole of it here.

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

vavi-sound sends them as `f0 45 04 sub ... f7` (`MfiValueExclusive`) next to the midi messages it
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

## TODO

* UCS against the dll: the dll has no exclusive for it, how the authoring tool gives it a UCS voice is not known (`param` +0x28?)
* mfi `0xe8`: the native player commits the low half of the pitch bend by it, a corpus analysis says it is not a part of the pitch bend (`nec/readme.md`), not sent
* `0xba` (channel configuration: drum family and pan mode of the native player)
* `0xb0`, `0xb1` of the UCS messages
