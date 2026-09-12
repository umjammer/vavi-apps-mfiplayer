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
    * a voice whose `RM` bit says *preset (rom) wave* has no data outside the chip
      and stays the OPL3's - every WT voice of the MA-7 samples is of that kind
    * the envelope rates are the OPL curve, not measured on an MA chip
* stream PCM
    * with `-Dvavi.sound.mobile.AudioEngine.disabled=true` vavi-sound sends the waves and
      their start / stop as exclusives (`vavi.sound.mobile.StreamExclusive`) instead of
      playing them itself, and `NukedWaveTable` plays them: a note of key 0 ~ 12 / 92 ~ on a
      drum channel (bank MSB `0x7d`) for a SMAF "Mobile Standard" file
    * without the flag the adpcm engine of vavi-sound still plays the stream waves and a
      stream note of a "Mobile Standard" file sounds nothing
* smaf FM voices of voice type `02` (AL) and `03` are not read, "GuitarMan.mmf" has one of each
