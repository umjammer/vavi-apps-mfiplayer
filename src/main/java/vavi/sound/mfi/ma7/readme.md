# vavi.sound.mfi.ma7

an mfi synthesizer that is the yamaha MA-7 (the sound source of the mfi/smaf phones of NEC, Panasonic ... of
its time) in pure java: the sound source itself is [`vavi.sound.ma7`](../../ma7/readme.md), a port of the MA-7
emulator of yamaha's android app "着信音設定" (`libM7_EmuSmw7.so`, arm64)

| class                | what                                                                                                                            |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `Ma7MfiSynthesizer`  | mfi synthesizer, receiver only: channel messages and the mfi values to the engine, adpcm to `VaviMfiSynthesizer#processSpecial` |

the sound source knows nothing of mfi: `Ma7MfiReceiver` is the whole of it here, and what it makes of the
mfi values goes to the engine as the bank of a channel (`Ma7AudioEngine#bankChange`) and the master volume
of the song (`#sourceExclusive`).

## the mfi

`Ma7MfiReceiver` takes the mfi values of vavi (`MfiValueExclusive`) as the library's own mfi converter
(`YAMAHA::MaMfiCnv`, type 9 of `MaSmw_Check`) takes the mfi bank (`0xe1`): the banks 4 ~ 13 as it sends the driver
for the 18 mld of an N703iD played by the library on the emulator (the program changes logged), 0 and 1, which none
of them has, as its code reads:

| bank     | melody channel                | drum channel                                                    |
|----------|-------------------------------|-----------------------------------------------------------------|
| not told | the bank of bank select msb   | the drums                                                       |
| 0, 1     | the program 0                 | the drums                                                       |
| 2 ~      | the program, odd banks + 0x40 | the drums (the drum program is bank & 1, which sounds the same) |

## TODO

* the rest of `MaMfiCnv` (mfi played by the library itself), its mode 1 (a program by the channel)
* the voices of the song itself: an mfi tone message comes as the very same exclusive a smaf one does
  (`vavi.sound.mfi.vavi.sequencer.YamahaMfiExclusive`, `f0 45 7f <43 79 07 7f 01 ...> f7`), so handing it to
  `vavi.sound.smaf.ma7.Ma7SmafVoices` would give an mfi song its own voices as a smaf one has them, see
  [`vavi.sound.smaf.ma7`](../../smaf/ma7/readme.md)
