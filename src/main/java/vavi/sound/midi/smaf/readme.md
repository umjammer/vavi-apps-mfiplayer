# package vavi.sound.midi.smaf

a java midi spi synthesizer that is the yamaha MA-7 playing a SMAF song (`.mmf`), in pure java

| name                       | status | comment                                                                 |
|----------------------------|:------:|-------------------------------------------------------------------------|
| SMAF MA-7 MIDI Synthesizer |   ✅️   | the rom read out of `libM7_EmuSmw7.so`, the streams by the adpcm engine |

the package of this name in vavi-sound has the synthesizer which plays a SMAF song on any midi
synthesizer (`SmafSynthesizer`, "Java MIDI(SMAF) Synthesizer"); this is the MA-7 itself, and its
classes are beside those.

## Usage

```java
Synthesizer synthesizer = MidiSystem.getMidiDevice(info); // "SMAF MA-7 MIDI Synthesizer"
synthesizer.open();
sequencer.setSequence(MidiSystem.getSequence(mmf)); // vavi-sound reads a song as a midi sequence
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
```

`libM7_EmuSmw7.so` has to be where [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md) looks for
it - `-Dvavi.sound.mfi.ma7.path=<file>`, the library or the apk it is in.

### what it is

`SmafMa7Synthesizer` is [`vavi.sound.midi.ma7.Ma7Synthesizer`](../ma7/readme.md) with the receiver of
[`vavi.sound.smaf.ma7`](../../smaf/ma7/readme.md) in place of the mfi one, which is all there is to
the difference between an mfi song and a smaf one here: the sound source, the channels and the
latency are the same, the messages of a song are taken as that receiver takes them (the banks of a
song, the stream waves and the streams a note starts, see its readme).

`openStream()` renders without a line as the mfi one does, where the streams of the adpcm engine are
not mixed in.

## TODO

- the instruments have no names in the rom, there is no soundbank
