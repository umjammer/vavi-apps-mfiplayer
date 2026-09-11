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
    * a VMA (MA-1/MA-2) voice (`43 03 nn ll pc ...`) needs **vavi-sound-ma 0.0.3 or
      later**; up to 0.0.2 `VMAFMVoice#ToVM35` sets into an empty operator list
      instead of adding to it, so the voice arrives with no operator at all
    * `43 79 xx 7f 00 / 03 / 07 / 0b / 0c / 0d / 7f`, `43 02 xx`, `43 01 80` and
      `43 04 xx` occur in a 1845 file corpus and are still unhandled
* wave table (WT) voices
    * an MFi/SMAF WT voice is no OPL3 timbre, so `NukedWaveTable` plays it through
      `vavi.sound.mobile.AudioEngine`, the engine the MFi 4.0 audio messages and the
      SMAF stream PCM already use, next to the OPL3 output
    * the note does not transpose the wave (it plays at its own `Fs`), and the loop
      point, the envelope, TL and panpot of the voice are not applied
    * a voice whose `RM` bit says *preset (rom) wave* has no data outside the chip
      and stays silent - every WT voice of the MA-7 samples is of that kind
