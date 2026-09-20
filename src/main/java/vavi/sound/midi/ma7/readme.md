# package vavi.sound.midi.ma7

a java midi spi synthesizer that is the yamaha MA-7 of the mobile phones, in pure java

| name                  | status | comment                                                  |
|-----------------------|:------:|----------------------------------------------------------|
| MA-7 MIDI Synthesizer |   ✅️   | the rom read out of `libM7_EmuSmw7.so`, ~45ms of latency |

## Usage

```java
Synthesizer synthesizer = MidiSystem.getMidiDevice(info); // "MA-7 MIDI Synthesizer"
synthesizer.open();
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
```

`libM7_EmuSmw7.so` has to be where [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md) looks for
it - `-Dvavi.sound.mfi.ma7.path=<file>`, the library or the apk it is in.

### what it takes

what the library's real time midi converter (`YAMAHA::MaRmdCnv`) takes

| message                       |                                                                                                                                              |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| CC 0 bank select msb          | latched by a program change: 0x79 melody, 0x78 drums, 0x7c, 0x7d banks of the library's table, any other the channel's default (9 the drums) |
| CC 1, 7, 10, 11, 64           | modulation (5 steps), volume, pan, expression, hold                                                                                          |
| CC 71, 74                     | resonance, brightness                                                                                                                        |
| CC 90, 91, 93                 | dry, reverb and chorus sends (the effects of the driver's dsp program are all but silent)                                                    |
| CC 101/100 + 6/38             | rpn 0 bend range (up to 24), 1 fine tune, 2 coarse tune                                                                                      |
| CC 120, 123 / 121             | all sound / notes off / reset all controllers                                                                                                |
| CC 126 (1), 127 (0)           | mono / poly                                                                                                                                  |
| pitch bend                    | the msb of it                                                                                                                                |
| `f0 7e 7f 09 01..03 f7`       | gm system on                                                                                                                                 |
| `f0 7f 7f 04 01 ll mm f7`     | master volume (the listener's, a gain after the sound source)                                                                                |
| `f0 7f 7f 04 03..04 ll mm f7` | master fine and coarse tuning                                                                                                                |
| `f0 43 79 06 7f 00 gg f7`     | the volume a song is to play at, the sound source's own beside the listener's master volume                                                 |
| `f0 43 79 06 7f 01 ...`       | a voice of a song, which its notes sound instead of one of the rom, see [`vavi.sound.ma7`](../../ma7/readme.md)                              |
| `f0 43 79 06 7f 03 ...`       | the wave a wave table voice of a song plays                                                                                                 |
| `f0 45 04 ...`                | the mfi values of vavi, see [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md)                                                                 |

channel pressure, poly pressure and nrpn are not taken, as the library does not. of the exclusives of yamaha only
the three above are: the voice messages of the MA-7 itself (`43 79 08 7f 21 ...`) and the rest (a user event, the
stream pair and panpot) are the player's, and a song whose voices are the MA-5 ones
(`43 79 07 7f ...`, the same voice 8 bit) has them packed into the form above by the one reading it, see
[`vavi.sound.smaf.ma7`](../../smaf/ma7/readme.md).

## TODO

- the instruments have no names in the rom, there is no soundbank
