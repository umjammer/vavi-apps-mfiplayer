# package vavi.sound.midi.smaf

a java midi spi synthesizer that is the yamaha MA-7 playing a SMAF song (`.mmf`), in pure java

| name                            | status | comment                                                                 |
|---------------------------------|:------:|-------------------------------------------------------------------------|
| SMAF MA-7 MIDI Synthesizer      |   ✅️   | the rom read out of `libM7_EmuSmw7.so`, the streams by the adpcm engine |
| SMAF MA-5 Live MIDI Synthesizer |   ✅️   | mmftool's `M5_EmuSmw5.dll` on jDOSBox, the streams by the adpcm engine  |

the package of this name in vavi-sound has the synthesizer which plays a SMAF song on any midi
synthesizer (`SmafSynthesizer`, "Java MIDI(SMAF) Synthesizer"); this is the MA-7 itself, and its
classes are beside those.

## Usage

```java
System.setProperty("vavi.sound.mobile.AudioEngine.disabled", "true"); // before the song is read, see below
Synthesizer synthesizer = MidiSystem.getMidiDevice(info); // "SMAF MA-7 MIDI Synthesizer"
synthesizer.open();
sequencer.setSequence(MidiSystem.getSequence(mmf)); // vavi-sound reads a song as a midi sequence
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
```

`vavi.sound.mobile.AudioEngine.disabled` is to be set, and set before the song is read: it is what tells
vavi-sound that the synthesizer plays the song's own voices, so that a percussion channel keeps its own
channel and its own program - the drum kit of the song. See
[`vavi.sound.smaf.ma7`](../../smaf/ma7/readme.md) for the whole of it; the name is not what it does.

`libM7_EmuSmw7.so` has to be where [`vavi.sound.mfi.ma7`](../../mfi/ma7/readme.md) looks for
it - `-Dvavi.sound.mfi.ma7.path=<file>`, the library or the apk it is in.

### what it is

`SmafMa7Synthesizer` is [`vavi.sound.midi.ma7.Ma7Synthesizer`](../ma7/readme.md) with the receiver of
[`vavi.sound.smaf.ma7`](../../smaf/ma7/readme.md) in place of the mfi one, which is all there is to
the difference between an mfi song and a smaf one here: the sound source, the channels and the
latency are the same, the messages of a song are taken as that receiver takes them (the banks and the
voices of a song, the stream waves and the streams a note starts, see its readme).

`openStream()` renders without a line as the mfi one does, where the streams of the adpcm engine are
not mixed in: the caller mixes them itself. `openStream(true)` mixes them in, on the bus before the
master volume and the clamp, as the line does; without either they play to lines of their own in
wall clock time, which is out of step with a song rendered ahead of it.

## SMAF MA-5 Live MIDI Synthesizer

`SmafMa5LiveSynthesizer` is yamaha's MA-5 emulator, `M5_EmuSmw5.dll` - the one [mmftool](https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool)
plays a SMAF file on - run on an emulated PC ([jDOSBox](https://github.com/umjammer/vavi-apps-dosbox)) under
`m5live.exe`, a front end of a few lines which is in the jar with its source
([`vavi/sound/smaf/ma5/m5live.c`](../../../../../resources/vavi/sound/smaf/ma5/m5live.c)). It plays through the door the
dll has for midi as it happens (`SetMidiMsg`, what mmftool's piano roll plays through), not through its file player,
so it is a synthesizer like any other and is used as the MA-7 one is above; the song is sequenced by the caller.

```shell
-Dvavi.sound.smaf.ma5.path=/usr/local/src/mmftool # where M5_EmuSmw5.dll, M5_EmuHw.dll and DefMA3_16.vm3 are
```

| property                        | default                  | what it is                                                                  |
|---------------------------------|--------------------------|-----------------------------------------------------------------------------|
| `vavi.sound.smaf.ma5.path`      | `/usr/local/src/mmftool` | the directory of the dlls (mmftool's) and of `DefMA3_16.vm3`                |
| `vavi.sound.smaf.ma5.rate`      | `32000`                  | what the dll synthesizes at: 22050, 32000, 44100 or 48000, it costs by rate |
| `vavi.sound.smaf.ma5.volume`    | `80`                     | how loud the dll plays, 0 ~ 127, a scale on the gain a song asks for        |
| `vavi.sound.smaf.ma5.queue`     | `4096`                   | frames between the emulated sound card and the listener                     |
| `vavi.sound.midi.smaf.ma5.line` | `2048`                   | frames of the host line                                                     |

what it took, which is none of it in mmftool's cli:

* the dll initialized for midi is the sound source without the driver above it, and it has **no voices**: a note
  of a program nobody gave a voice is silence. mmftool's gui gives it the ones of `DefMA3_16.vm3` (`PresetVoices`),
  and so does this, `vavi.sound.smaf.ma5.Ma5Voices`. the dll has the melody banks (bank select lsb) 0 ~ 9 and
  the drum kits 0 ~ 9 and refuses the rest: the bank 10 and the kit 10 of the file are left out
* the setup is yamaha's ATS-MA5's, as mmftool's memo (`M5Emu2.txt`) has it, not mmftool's: four more
  `MaSound_DeviceControl`s and `43 79 06 7f 13 08`, `11 00`, `07 00`. the dll's waveOut is fed from a thread of
  its own either way
* the master volume (`43 79 05 7e 09 vv`, or `06 7e 09`) changes nothing; the gain of the song (`43 79 0x 7f 00 gg`)
  does, and the dll clips at its top, so `vavi.sound.smaf.ma5.volume` scales that. "GuitarMan.mmf" asks for 110,
  which clips 1.3% of its samples, and none at 80% of it
* the voices of a song go to the dll in the MA-3 real time form (`vavi.sound.smaf.ma7.Ma7SmafVoices`, which packs
  an MA-5 one into it as mmftool does), each after the message mmftool sends to clear its place (`02`, `04`)
* the streams of a song are played by the adpcm engines of vavi-sound and mixed into the line, started as late as
  the dll's notes are heard

it plays live, so there is a latency (`getLatency()`, about 200ms by default), and jDOSBox keeps its machine in
statics: one of these open is the only one, and no faith synthesizer can run beside it.

## TODO

- MA-5: the stream exclusives of a song (`0b` the panpot, `0d`), which mmftool does not give the dll either
- MA-5: a voice of the song of the type 2 or 3 loses its filter ("AL") in the MA-3 form it goes to the dll in
- the instruments have no names in the rom, there is no soundbank
- the voices of a song replace the ones of the rom for as long as it plays, and are not a soundbank either
