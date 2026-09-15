# vavi.sound.mfi.ucs

the fuetrek sound source (faith Type 4, docomo UCS) in pure java

| class            | what                                                                                                                              |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `UcsSynthesizer` | mfi synthesizer, receiver only: channel messages to the engine, exclusives (UCS waves, adpcm) to `VaviSynthesizer#processSpecial` |
| `UcsAudioEngine` | 32 kHz, 32 voices, midi channels → preset tones or UCS waves                                                                      |
| `FuetrekVoice`   | 2 pcm/noise oscillators, tone shape filter, envelopes A/B, lfo                                                                    |
| `FuetrekRom`     | the preset tones read out of the installed `rt_synth_4.dll`                                                                       |
| `UcsSequencer`   | the UCS waves of a file (`0x10` ~ `0x12`)                                                                                         |

## Usage

### system properties

- `vavi.sound.mfi.faith.path` ... the authoring tool's `Tools` directory, where `rt_synth_4.dll` is (see [faith](../faith/readme.md))

nothing of the dll is distributed, it is read at `open()`.

## rt_synth_4.dll

2003-08-28 build (PE time stamp `0x3f4d685e`), a sibling of DoJa 5.1 sdk's `MFiSynth_ft.dll`
(173 of the 182 samples are the same pcm)

| what                       | where / how                                                                        |
|----------------------------|------------------------------------------------------------------------------------|
| groups                     | found by structure: `0x79` melody (128), `0x78` drum (47), `0x7d` (6), `0x14` (32) |
| instrument / zone / sample | `0x08` / `0x44` / `0x24` records, pointers from the groups                         |
| pitch ratio                | `0x1000f2e4`                                                                       |
| root key tune              | `0x1000f210`                                                                       |
| mix profiles               | `0x10011030`, `0x10011438`, `0x10011840`                                           |
| note shape / drum pan      | `0x10011030` / `0x10011c48`                                                        |
| pan law                    | `0x10011cc8`                                                                       |
| control curves (12)        | `0x10011dc8` ~ `0x10012300`                                                        |
| gain / stereo curve        | `0x10012388` / `0x10012488`                                                        |
| interpolation              | `0x10012d20`                                                                       |

## UCS messages

vendor/carrier `0x71` (sharp) and `0x41` (panasonic, P905i, P705i use fuetrek too, not confirmed by a sample yet)

| function       | contents                                                                                                                                                                     |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0x10`         | wave `#`, type 1: length(3) loopStart(3) loopEnd(3) / type 2: length(2) signed 8 bit pcm                                                                                     |
| `0x11`         | wave `#`, `0x02`, length `0x2c`, the 44 byte voice parameters of the sound source (the "voice edit" control), `[0]` bit 0: uploaded wave, `[6]` root key, `[7]` encoded tune |
| `0x12`         | wave `#`, `0x00`, length `0x04`, `0x80 0x00` bank program                                                                                                                    |
| `0xb0`, `0xb1` | ?                                                                                                                                                                            |

* a wave is played by the notes of the mfi (bank, program) `0x12` assigns it to
* the tune of `[7]` is the dll's root key tune table, `Judgment_ft.mld` wave 1 comes out at key 67.07 for 67

## banks

vavi's midi program keeps only bit 0 of an mfi bank, so the converter (`ChangeBankMessage`) also sends
the bank as it is by `f0 45 04 channel bank f7`, which the other synthesizers let go.

| bank          | melody channel            | drum channel (9)   |
|---------------|---------------------------|--------------------|
| not told      | `0x79` by the midi program | `0x78` by note     |
| 0             | `0x7d` (mfi 1 square/sine, 0 ~ 5) | `0x78`     |
| 1 ~ 0x33      | `0x79`, odd banks + 0x40  | `0x78`             |
| 0x34          | -                         | `0x14` by note (35 ~ 66) |
| 0x36          | `0x11` → `0x79`           | `0x10` → `0x78`    |

## compared with the dll

rendered 30 s by `FaithType4Renderer` (the dll on jdosbox) and by `UcsAudioEngine`

| mld                                   | loudness envelope correlation | level (java / dll) |
|---------------------------------------|-------------------------------|--------------------|
| `PoN_jar5/mld_1.mld`                  | 0.89                          | 1.31               |
| `tmp/ucs/Judgment_ft.mld` w/o UCS     | 0.99                          | 1.33               |

## References

* openDoJa `opendoja.audio.mld.fuetrek.FueTrekSampler` ... the behaviour of the voice (envelopes, lfo, filter, pitch) is as recovered there

## TODO

* UCS against the dll: `FaithType4Renderer` does not hand the UCS waves to the dll, what its exclusive for them is not known
* UCS pcm is shifted to the 6 bit amplitude of the rom waves, not confirmed
* the level is 1.3 times the dll's
* `0xb0`, `0xb1`
* working out the DLL's exclusive message format
