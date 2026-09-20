# vavi.sound.mfi.rohm

an mfi synthesizer that is the rohm sound source (faith Type 2, BU8788KN / BU8709KN of F504i ~ F901iS, D901i,
P900i ~ P901iS, SH251i/505i) in pure java: the sound source itself is [`vavi.sound.rohm`](../../rohm/readme.md)

| class                | what                                                                                                                            |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `RohmMfiSynthesizer` | mfi synthesizer, receiver only: channel messages and the mfi values to the engine, adpcm to `VaviMfiSynthesizer#processSpecial` |

the sound source knows nothing of mfi: `RohmMfiReceiver` is the whole of it here, and what it makes of the
mfi values goes to the engine as the bank of a channel (`RohmAudioEngine#bankChange`) and the master volume
of the song (`#sourceExclusive`).

## the mfi

`RohmMfiReceiver` takes the mfi values of vavi (`MfiValueExclusive`) as the fuetrek sound source does
([ucs](../ucs/readme.md)), the groups of the rohm one being the same:

| bank     | melody channel               | drum channel (9) |
|----------|------------------------------|------------------|
| not told | the group of bank select msb | 0x78             |
| 0        | 0x7d                         | 0x78             |
| 1 ~ 0x33 | 0x79, odd banks + 0x40       | 0x78             |
| 0x34     | -                            | 0x14             |
| 0x36     | 0x11 (UCS)                   | 0x78             |

## TODO

* UCS of an mfi file: the machine dependent messages of rohm (`0x10` ~ `0x12` of F, P and SH) are not known,
  no sample. the dll's own is `RohmSoundSource#ucsPcm`, `#ucsWaves`, `#ucsZones`
* the mfi bank is the fuetrek one, `rt_player_2.dll` (faith's mfi player of Type 2) is not looked into
* `0xe9` (the fine half of the pitch bend) and `0xe7` of mfi as the rohm native player takes them
