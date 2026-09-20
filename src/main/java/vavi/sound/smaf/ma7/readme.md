# vavi.sound.smaf.ma7

the yamaha MA-7 as the sound source of a SMAF song (`.mmf`), the smaf spi of vavi-sound
([`vavi.sound.smaf`](https://github.com/umjammer/vavi-sound/tree/master/src/main/java/vavi/sound/smaf))
on the engine of [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md)

| class                   | what                                                                                                                    |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `Ma7SmafSynthesizer`    | smaf synthesizer, receiver only: channel messages and the exclusives of a song to the engine, the streams to the adpcm  |
| `Ma7SmafVoices`         | the voices and the waves of the song, in the form the sound source takes them                                           |
| `Ma7SmafDeviceProvider` | the smaf device provider offering it                                                                                     |

the midi spi synthesizer on the same receiver is [`vavi.sound.midi.smaf`](../../midi/smaf/readme.md).

## Usage

```java
System.setProperty("vavi.sound.mobile.AudioEngine.disabled", "true"); // see below, this one is needed
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

### `vavi.sound.mobile.AudioEngine.disabled` is to be ON

the property is vavi-sound's and it has to be set **before the song is read**, because it is the reading
which it changes. its name is what it once meant; what it does is to tell a synthesizer which plays a
song's own voices from one which plays a GM bank, and it is the only thing that does - in vavi-sound 1.1.3
`AudioEngine#isDisabled` is read by `MidiContext` and `ProgramChangeMessage` of the smaf spi and by nothing
else. of a "Mobile Standard" song it decides what becomes of a percussion channel:

| the property | a percussion channel of a song becomes                                                      |
|--------------|-----------------------------------------------------------------------------------------------|
| off (default) | midi channel 9, and its program 0 - one drum channel, a GM drum kit                          |
| **on**       | its own channel with its own program, which is the drum kit of the song (`Bank_Program3` of the MA-3 driver) |

the MA-7 is a synthesizer of the second kind:

* its drums are the song's own voices of the bank 0x7d, whose **program is the kit** - with the property off
  the program is 0 and the kit of the song is never selected
* the streams of a song are notes of a channel of the bank 0x7d too (see the table below), which must not be
  thrown in with the drums on channel 9: "GuitarMan.mmf" has its drums on one such channel and its streams on
  another, and off they are both channel 9

the streams are played by the adpcm engines of vavi-sound either way - no wave travels differently for this
property, so "disabled" names nothing that happens here. `Ma7SmafReceiver` logs a warning when it is off.

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
| `f0 45 7f <43 79 0x 7f 01 ...> f7`   | a voice of the song, which its notes sound instead of one of the rom, see `Ma7SmafVoices`                                  |
| `f0 45 7f <43 79 0x 7f 03 ...> f7`   | the wave a wave table voice of the song plays                                                                              |

the streams are mixed into the engine's line (`AudioEngineMixer`), so they sound in the song and not
beside it, and are not timed by the wall clock. a stream is started by the message which starts it and
stopped by the note off or the gate time of the start.

### the voices of the song

`Ma7SmafVoices` hands them to the sound source, see [`vavi.sound.ma7`](../../ma7/readme.md) for what it
does with them. a song has them as the MA-3 ones (`43 79 06 7f ...`, packed 7 bit) or the MA-5 ones
(`43 79 07 7f ...`, the very same data 8 bit, which "GuitarMan.mmf" has); the sound source takes the MA-3
form, as the library does, so an MA-5 one is packed into it. two things a song does which the sound source
does not follow by itself:

* a voice of the type 2 or 3 has a filter ("AL") before the voice itself, which the MA-7's real time midi
  path has nothing of: the voice is taken without it, so a song sounds its own voice rather than one of the
  rom and loses only the filter
* the wave of a wave table voice has to be registered before the voice which plays it, and a song may send
  them the other way round ("GuitarMan.mmf" does): such a voice waits until its wave arrives

## TODO

* the exclusives of yamaha a song has which are the player's, all of which are logged and nothing else:
  the master volume (`00`), the stream pair (`08`) and the stream panpot (`0b`)
* a song whose wave table waves and voices come as the chunks of a file rather than as the setup exclusives:
  vavi-sound sends those as `43 05 00 id <wave>` ("EXWV"), `43 05 02 bb pp <voice>` ("EXVO") and
  `43 05 01 ll pc <voice>`, which are the same data in another wrapper and are not taken here yet - such a
  voice waits for a wave which never comes and the note sounds a voice of the rom, as it did before.
  `vavi.sound.midi.ymf262.YamahaVoices` reads all three
* the same voices in an mfi song, which vavi-sound sends as the very same exclusives
  (`vavi.sound.mfi.vavi.sequencer.YamahaMfiExclusive`): `vavi.sound.mfi.ma7` could hand them to
  `Ma7SmafVoices` as this does
* the filter ("AL") of a voice, and the voice messages of the MA-7 itself (`43 79 08 7f 21 ...`)
* the streams of the MA-7 itself, which would play them where the adpcm engine does now, see the TODO of
  [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md)
* the dsp program of SMAF, the driver's is what `Ma7Dsp2` knows
