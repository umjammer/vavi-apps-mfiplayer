# package vavi.sound.midi.faith

a java midi spi synthesizer that is the Type 4 (fuetrek) voice engine out of faith's
"Ring Tone Authoring Tool", played live

| name                          | status | comment                                        |
|-------------------------------|:------:|------------------------------------------------|
| Faith Type4 MIDI Synthesizer  |   ✅️   | `rt_synth_4.dll` on jdosbox, ~90ms of latency  |

## Usage

```java
Synthesizer synthesizer = MidiSystem.getMidiDevice(info); // "Faith Type4 MIDI Synthesizer"
synthesizer.open();
sequencer.getTransmitter().setReceiver(synthesizer.getReceiver());
```

`rt_synth_4.dll` has to be where [`vavi.sound.mfi.faith`](../../mfi/faith/readme.md) looks for
it - `-Dvavi.sound.mfi.faith.path=<dir>`.

### system properties

everything [`vavi.sound.mfi.faith`](../../mfi/faith/readme.md) takes, and

- `vavi.sound.midi.faith.line` ... the host line's buffer, in frames, default 2048

## How

the dll is a synthesizer with no clock and no sequencer, and it only runs on an x86 windows, so
it runs on jdosbox - see [`vavi.sound.mfi.faith`](../../mfi/faith/readme.md), which is where the
machine is. `FaithType4Player` there hands it a whole song at once, which is no use to a spi
synthesizer that is handed one message at a time; `FaithType4Device` is the same dll in
`rts4c.exe`'s `-live` mode, where messages arrive down a file the host keeps appending to and are
played at the next block. this is the `javax.sound.midi` skin over that.

## TODO

- the dll's voice sets cannot be read out, so there is no soundbank
