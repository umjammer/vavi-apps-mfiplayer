# vavi.sound.mfi.ma7

the yamaha MA-7 (the sound source of the mfi/samf phones of NEC, Panasonic ... of its time) in pure java, a port of
the MA-7 emulator of yamaha's android app "着信音設定" (`libM7_EmuSmw7.so`, arm64)

| class                                | what                                                                                                                            |
|--------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `Ma7MfiSynthesizer`                  | mfi synthesizer, receiver only: channel messages and the mfi values to the engine, adpcm to `VaviMfiSynthesizer#processSpecial` |
