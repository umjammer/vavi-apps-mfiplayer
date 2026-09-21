[![Release](https://jitpack.io/v/umjammer/vavi-apps-mfiplayer.svg)](https://jitpack.io/#umjammer/vavi-apps-mfiplayer)
[![Java CI](https://github.com/umjammer/vavi-apps-mfiplayer/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-apps-mfiplayer/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-apps-mfiplayer/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-apps-mfiplayer/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-apps-mfiplayer

<img alt="logo" src="src/test/resources/duke_accordion.png" width="160" />

♬ MFi Player w/ OPL3 synthesizer

| type        | synth   | receiver   | how        | status | comment                               |
|-------------|---------|------------|------------|:------:|---------------------------------------|
| smaf        | Nuked   | VaviSmaf   | pure java  |  ✅️🚧  | only adpcm                            |
| smaf        | Nuked   | -          | pure java  |   ✅️   |                                       |
| mfi:fuetrek | Faith   | VaviMfi    | dll on emu |  ✅️🚧  | TODO heavy, timing, send adpcm to dll |
| mfi:fuetrek | Fuetrek | FuetrekMfi | pure java  |   ✅️   | uses `AudioEngine` inside             |
| mfi:yamaha  | Nuked   | VaviMfi    | pure java  | ✅️🚧️  | only adpcm                            |
| mfi:yamaha  | Nuked   | -          | pure java  |   ✅️   | sample needed                         |
| mfi:rohm    | Rohm    | RohmMfi    | pure java  |   ✅️   | bit exact to `rt_synth_2.dll`, no UCS |
| mfi:yamaha  | Ma7     | Ma7Mfi     | pure java  |   ✅️   | bit exact to `libM7_EmuSmw7.so`       |
| smaf        | Ma7     | Ma7Smaf    | pure java  |   ✅️   | the engine of `mfi:yamaha`, adpcm     |

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-apps-mfiplayer)

## Usage

### system property
   * `vavi.sound.midi.ymf262.soundbank` ... a soundbank file for the synthesizer to play, read
     through the `SoundbankReader` spi. Nothing named means the OPL3 (YMF262) bank each
     synthesizer comes with, which is what they were made for. Both of them take it.
     An MA-3 preset voice library (`.vm3`, "FMM3") is one such file, e.g. `DefMA3_16.vm3` of
     [mmftool](https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool): its FM
     voices are what gives a wave table voice a timbre, the drum kit next to the standard one
     holding an FM voice for every note the standard one plays a rom wave for. It is read into
     the OPL3 bank of this package (`YmF262Soundbank`, what a `.sbi` or `.o3` is read into as
     well), so by hand it is
     `synthesizer.loadAllInstruments(MidiSystem.getSoundbank(new File("DefMA3_16.vm3")))`.

   * `vavi.sound.mobile.AudioEngine.volume` ... the volume of the line an adpcm engine of
     vavi-sound opens for itself (default 0.2). When a synthesizer here mixes the streams into a
     song instead, no such line is opened and this does not apply: the synthesizer says how loud
     they are, `vavi.sound.ma7.adpcm` for the MA-7 (default 1, level with the sound source).

   * `vavi.sound.mobile.AudioEngine.disabled` ... what its name says is not what it does. In
     vavi-sound 1.1.3 it is read by `MidiContext` and `ProgramChangeMessage` of the smaf spi and
     by nothing else, so it changes one thing only, and only for a "Mobile Standard" SMAF file:
     what a percussion channel of it becomes when the file is read as a midi sequence. It is to be
     set **before the file is read**, being the reading it changes; the waves of a file travel the
     same either way.
     * with the flag (`true`) ... a percussion channel keeps its own midi channel and its own
       program, which is the drum kit of the file (`Bank_Program3` of the MA-3 driver). This is
       for a synthesizer which plays the file's own voices - the MA-7 ones
       ([`vavi.sound.smaf.ma7`](src/main/java/vavi/sound/smaf/ma7/readme.md),
       [`vavi.sound.midi.smaf`](src/main/java/vavi/sound/midi/smaf/readme.md)) and the OPL3 ones
       with the voices of the file loaded - and it also keeps the streams of a file, which are
       notes of key 0 ~ 12 / 92 ~ on a channel of the bank MSB `0x7d`, off the drum channel
     * without the flag (`false`) ... every percussion channel goes to midi channel 9 and its
       program becomes 0, which is what a GM synthesizer wants

## References

 * https://github.com/Wohlstand/OPL3BankEditor
 * https://github.com/DM-88mkII/OPLx-TimbreEditor
 * https://github.com/denjhang/OPLSynth (banks)
 * https://gist.github.com/bryc/e85315f758ff3eced19d2d4fdeef01c5#gistcomment-3704767
 * https://ltva1.github.io/MA-7/ma-7.html
 * https://github.com/wegi1/MA3_YMU762_AND_DISCOVERY_F407VG
 * https://github.com/nukeykt/WinOPL3Driver 🎯
 * https://github.com/noway2pay/YMF825_sample
 * https://keim.hatenablog.com/entry/20080827/p1
 * https://docs.google.com/spreadsheets/d/1vA5V1RVu62bW5hsOeuh0jwxBbzRQtnMfmhNjPHSWKWM/edit?gid=1370745390#gid=1370745390 🔐
 * smaf
   * https://github.com/mmontag/mmfplay 🎯
   * https://github.com/Rockbox/rockbox/blob/master/lib/rbcodec/codecs/smaf.c
   * https://github.com/shirajira/OpenMF (c++)
   * https://github.com/SatyrDiamond/random_parsers (python)
   * https://github.com/dlunch/smaf (rust)
   * https://github.com/Pusungwi/mmf_parser (rust)
 * mfi
   * https://github.com/logue/smfplayer.js

## TODO

 * ~~nuked soundfont~~
 * yamaha
   * https://github.com/umjammer/vavi-sound-sion
   * https://github.com/umjammer/vavi-sound-ma
   * https://github.com/dlawoals2713/MMF-Player/blob/master/app/src/main/java/com/yamaha/smafsynth/m7/emu/EmuSmw7.java
   * https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool
   * https://github.com/akustikrausch/yamaha-smaf-player
 * fuetrek
   * faith ucs ... https://github.com/umjammer/vavi-sound/pull/30
 * rohm
   * mfmp ... https://sourceforge.net/projects/retrocode/ (/usr/local/src/retrocode) 🏡
   * https://github.com/wackypack/mtex
 * ~~sysex wiring~~
 * ~~test openDoja synthesizer~~ ... vavi-sound--sandbox
 * ~~ma# timbre~~ ... `DefMA3_16.vm3`, the FM kit is the timbre of a rom wave note

---

<sub>image designed by @umjammer, drawn by nano banana</sub>
