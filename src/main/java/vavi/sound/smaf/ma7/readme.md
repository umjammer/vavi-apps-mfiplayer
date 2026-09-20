# vavi.sound.smaf.ma7

the yamaha MA-7 as the sound source of a SMAF song (`.mmf`), the smaf spi of vavi-sound
([`vavi.sound.smaf`](https://github.com/umjammer/vavi-sound/tree/master/src/main/java/vavi/sound/smaf))
on the engine of [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md)

| class                   | what                                                                                                                    |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `Ma7SmafSynthesizer`    | smaf synthesizer, receiver only: channel messages and the exclusives of a song to the engine, the streams to the adpcm  |
| `Ma7SmafDeviceProvider` | the smaf device provider offering it                                                                                     |

the midi spi synthesizer on the same receiver is [`vavi.sound.midi.smaf`](../../midi/smaf/readme.md).

## Usage

```java
System.setProperty("vavi.sound.samf.Synthesizer", "#Java SMAF MA-7 Synthesizer"); // sic, the key of vavi-sound
Synthesizer synthesizer = SmafSystem.getSynthesizer();
Sequencer sequencer = SmafSystem.getSequencer();
sequencer.open();
synthesizer.open();
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
sequencer.setSequence(SmafSystem.getSequence(file));
```

`libM7_EmuSmw7.so`, where the rom is, has to be where [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md)
looks for it - `-Dvavi.sound.mfi.ma7.path=<file>`, the library or the apk it is in.

`vavi.sound.mobile.AudioEngine.disabled` is to be off (the default): the stream waves of a song then
come as the exclusives of vavi and are played by the adpcm engines of vavi-sound, which is what plays
them here - the MA-7 has no streams of its own yet.

### what it takes

vavi-sound converts a song into midi ([`vavi.sound.smaf.vavi.VaviSmafMidiConverter`](https://github.com/umjammer/vavi-sound/tree/master/src/main/java/vavi/sound/smaf/vavi/VaviSmafMidiConverter.java)),
and of what comes out

| message                              |                                                                                                                            |
|--------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| the channel messages                 | to the sound source as they come, see [`vavi.sound.midi.ma7`](../../midi/ma7/readme.md) for what it takes of them           |
| CC 0 / 32 bank select                 | 0x7c a melody voice, 0x7d a percussion one: the banks of the library's table, which is what the MA-7 driver knows them as   |
| a note of the key 0 ~ 12 / 92 ~ 110 on a channel of the bank 0x7d | a stream of a "Mobile Standard" song (the wave `key + 1` / `key - 78`), as `Note_ON3` of the MA-3 driver (`mammfcnv.c`) plays it: the adpcm engine plays it and the sound source does not see the note |
| `f0 45 7f <45 03 10 ...> f7`         | a stream wave of the song ("Mwa\*", "Awa\*"), to the adpcm engine                                                           |
| `f0 45 7f <45 03 11 / 12 ...> f7`    | the start and the stop of a stream of a "Handy Phone Standard" song                                                        |
| `f0 7e / 7f ...`                     | gm system on, the master volume (the listener's, a gain after the sound source), the tunings                                |

the streams are mixed into the engine's line (`AudioEngineMixer`), so they sound in the song and not
beside it, and are not timed by the wall clock. a stream is started by the message which starts it and
stopped by the note off or the gate time of the start.

## TODO

* the exclusives of yamaha a song has (`43 79 0x 7f ...`), all of which are logged and nothing else:
  the master volume (`00`), the voices (`01`) and the waves (`03`) of the song, which the MA-7 plays
  its own instead of, the stream pair (`08`) and the stream panpot (`0b`)
* the streams of the MA-7 itself, which would play them where the adpcm engine does now (and would
  make `vavi.sound.mobile.AudioEngine.disabled` the mode to use), see the TODO of
  [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md)
* the dsp program of SMAF, the driver's is what `Ma7Dsp2` knows
