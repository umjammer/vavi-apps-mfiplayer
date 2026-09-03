# vavi.sound.mfi.faith

a synthesizer using faith `rt_synth_4.dll` running on jDOSBox emulator

| class               | plays                                          |
|---------------------|------------------------------------------------|
| `FaithType4Player`  | a whole song, timed here and handed over at once |
| `FaithType4Device`  | one message at a time, as it arrives - what [`vavi.sound.midi.faith`](../../midi/faith/readme.md) is made of |

## Usage

### system properties

- `vavi.sound.mfi.faith.path` ... authoring tool's directory
- `vavi.sound.mfi.faith.cushion` ... audio buffer, default 2 (`FaithType4Player`)
- `vavi.sound.mfi.faith.memory` ... memory usage, default 32
- `vavi.sound.mfi.faith.core` ... which cpu using, default `dynamic`
- `vavi.sound.mfi.faith.gain` ... n * 256, default 3
- `vavi.sound.mfi.faith.mode` ... 0 and 1: have 48 voices, 2: has 64
- `vavi.sound.mfi.faith.live.blocks` ... blocks to a waveOut buffer, default 4 (`FaithType4Device`)
- `vavi.sound.mfi.faith.live.buffers` ... waveOut buffers in flight, default 4
- `vavi.sound.mfi.faith.live.queue` ... frames between the machine and the caller, default 2048

the last three are the latency: `(blocks * buffers * 128 + queue) / 44100`, about 90ms as it
stands. smaller is nearer the key press and nearer the emulator running out of road.

## References

- [faith "Ring Tone Authoring Tool"](https://lpcwiki.miraheze.org/wiki/Ringtone_file_formats#MFi_(.MLD)) ... `rt_synth_4.dll` is the Type 4 (fuetrek) synthesizer,
  played on jdosbox through its waveOut by `vavi.sound.mfi.faith.FaithType4Player` (a song at
  a time) and `vavi.sound.mfi.faith.FaithType4Device` (a message at a time, which is what the
  `vavi.sound.midi.faith` midi spi synthesizer is) 🎯

## TODO

* ~~faith type4 (`rt_synth_4.dll` on jdosbox)~~
* ~~faith type4 midi spi synthesizer~~ ... [`vavi.sound.midi.faith`](../../midi/faith/readme.md)
