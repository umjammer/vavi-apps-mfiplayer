# package vavi.sound.midi.ymf262

### status

#### synthesizer

| type     | status | comment |
|----------|:------:|---------|
| matsuoka |   ✅️   |         |
| nuked    |   ✅️   |         |

#### soundbank reader

| type | status | comment |
|------|:------:|---------|
| ibk  |   ✅️   |         |
| sbi  |   ✅️   |         |

## Usage

- `VaviSynthesizer` + 

## TODO

* smaf voices
    * `43 79 xx 7f 00 / 07 / 0c / 0d / 7f`, `43 02 xx`, `43 01 80` and
      `43 04 xx` occur in a 1845 file corpus and are still unhandled
* wave table (WT) voices
    * `NukedWaveTable` is a sampler of its own mixed into the OPL3 output: the pitch
      follows the key (`Fs` is key 60, a drum voice always key 60), the loop point,
      AR / DR / SL / SR / RR / XOF / SUS, TL, the voice panpot, velocity, channel volume,
      expression, pan and pitch bend are applied
    * LFO, DAM / DVB and the reduced key follow of melody programs 115 ~ 127 are not
    * a voice whose `RM` bit says *preset (rom) wave* plays wave 0 ~ 6 of the MA-3 / MA-5
      rom (`MaRomWaves`), which is not shipped: `-Dvavi.sound.midi.ymf262.waveTable.rom=<file>`
      names a 16KB rom image or a file holding one, e.g. `M5_EmuHw.dll` of "ATS-MA5-SMAF".
      without it such a voice stays the OPL3's (a `.vm3` soundbank gives it the FM kit's timbre).
      ~2900 voices of a ~2000 file corpus are of that kind, the drums mostly
    * the 7 rom waves are in `libM7_EmuSmw7.so` of the MA-7 "Ringtone Settings" app too, but
      rearranged, not as a rom image; `43 79 08 ..` MA-7 voices are not handled
    * the envelope rates are the OPL curve, not measured on an MA chip
* smaf FM voices of voice type `02` (AL) and `03` are not read, "GuitarMan.mmf" has one of each
