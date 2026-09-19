# package vavi.sound.midi.ucs

a java midi spi synthesizer that is the fuetrek sound source (UCS) of the mfi phones, in pure java

| name                 | status | comment                                                   |
|----------------------|:------:|-----------------------------------------------------------|
| UCS MIDI Synthesizer |   ✅️   | the rom read out of `rt_synth_4.dll`, ~40ms of latency    |

## Usage

```java
Synthesizer synthesizer = MidiSystem.getMidiDevice(info); // "UCS MIDI Synthesizer"
synthesizer.open();
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
```

`rt_synth_4.dll` has to be where [`vavi.sound.mfi.ucs`](../../mfi/ucs/readme.md) looks for
it - `-Dvavi.sound.mfi.faith.path=<dir>`.

### what it takes

| message                         |                                                                                      |
|---------------------------------|--------------------------------------------------------------------------------------|
| CC 0 bank select msb            | the rom group, latched by a program change: 0x79 melody, 0x78 drum, 0x7d, 0x11 (odd melody, even drum, a group the rom has not is 0x79 / 0x78 by its bit 0), 0 the channel's default |
| CC 1, 7, 10, 11, 64             | modulation, volume, pan, expression, hold                                            |
| CC 101/100 + 6/38, 96/97        | rpn 0 bend sensitivity, 1 fine tuning, 2 coarse tuning                               |
| CC 120, 123 / 121               | the notes of the channel cut / released / reset all controllers                      |
| channel pressure                | added to the modulation                                                              |
| pitch bend                      |                                                                                      |
| `f0 7e 7f 09 01 f7`             | gm system on                                                                         |
| `f0 7f 7f 04 01..04 ll mm f7`   | master volume (the listener's, a gain after the sound source), balance, fine and coarse tuning |
| `f0 45 04 ...`                  | the mfi values of vavi, see [`vavi.sound.mfi.ucs`](../../mfi/ucs/readme.md)         |
| the other exclusives            | the adpcm and the UCS waves of the machine dependent messages, as `UcsMfiSynthesizer` |

poly pressure and nrpn are not taken. `UcsSynthesizer#setVolume` is the listener's volume.

## TODO

- the instruments have no names in the rom, there is no soundbank
