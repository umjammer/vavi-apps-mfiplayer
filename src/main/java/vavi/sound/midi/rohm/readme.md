# package vavi.sound.midi.rohm

a java midi spi synthesizer that is the rohm sound source of the mfi phones, in pure java

| name                  | status | comment                                                   |
|-----------------------|:------:|-----------------------------------------------------------|
| Rohm MIDI Synthesizer |   ✅️   | the rom read out of `rt_synth_2.dll`, ~30ms of latency    |

## Usage

```java
Synthesizer synthesizer = MidiSystem.getMidiDevice(info); // "Rohm MIDI Synthesizer"
synthesizer.open();
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
```

`rt_synth_2.dll` has to be where [`vavi.sound.mfi.rohm`](../../mfi/rohm/readme.md) looks for
it - `-Dvavi.sound.mfi.faith.path=<dir>`.

### what it takes

| message                         |                                                                                      |
|---------------------------------|--------------------------------------------------------------------------------------|
| CC 0 bank select msb            | the group, latched by a program change: 0x79 melody, 0x7d, 0x11, an even one a drum set (0x14 the second), 0 the channel's default |
| CC 1, 7, 10, 11, 64             | modulation, volume, pan, expression, hold                                            |
| CC 101/100 + 6/38, 96/97        | rpn 0 bend sensitivity                                                               |
| CC 120, 123 / 121               | the notes of the channel cut / reset all controllers                                |
| pitch bend                      |                                                                                      |
| `f0 7e 7f 09 01 f7`             | gm system on, the reverb preset 0 (dry)                                              |
| `f0 7f 7f 04 01..04 ll mm f7`   | master volume (the listener's, a gain after the sound source), balance, fine and coarse tuning |
| `f0 45 04 ...`                  | the mfi values of vavi, see [`vavi.sound.mfi.rohm`](../../mfi/rohm/readme.md)       |

channel pressure, poly pressure and nrpn are not taken, as the dll does not. `RohmSynthesizer#setReverb`
is the reverb of the dll (0 ~ 7, -1 off), which midi has no message for there.

## TODO

- the instruments have no names in the rom, there is no soundbank
