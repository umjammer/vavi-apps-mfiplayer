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

## the voices of the song are not played, as the library does not play them

an mfi song usually brings voices of its own - all 18 of the N703iD mld, and 96 of 400 of the `~/Public/np2/mfi`
corpus, 1639 voices between them - and vavi-sound hands them to the receiver as the very same exclusives a smaf
song's come as (`vavi.sound.mfi.vavi.sequencer.YamahaMfiExclusive`, `f0 45 7f <43 79 07 7f 01 ...> f7`, the bank
msb and lsb 0 and the program the mfi bank and program collapsed by `MidiContext#toProgram`).

they are not taken here, because the library does not take them either: `MaMfiCnv_ReqVoice` is a stub
(`movz w0, 0; ret`), nothing of `MaMfiCnv` calls `MaSndDrv_SetVoice`, and an mfi song is played on the voices of
the rom which the bank table above selects. only the real time midi (`MaRmdCnv`), the smaf (`MaMmfCnv`) and the
phrase (`MaPhrCnv`) converters of the library register a song's voices, see
[`vavi.sound.smaf.ma7`](../../smaf/ma7/readme.md) for the smaf one.

playing them anyway would be a deviation from the library, not a port of it: the exclusive's bank msb is 0 and
not the 0x7c / 0x7d the sound source knows, so a voice would have to be registered where `Ma7AudioEngine#programChange`
sends the note instead - the driver's bank 0 with the exclusive's program for a melody voice, the bank 0x80 with
its drum note for a drum one - and there is no ground truth to check that against.

## TODO

* the rest of `MaMfiCnv` (mfi played by the library itself), its mode 1 (a program by the channel)
