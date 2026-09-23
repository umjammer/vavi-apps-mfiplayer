# vavi.sound.mfi

package for DoCoMo MFi vendor implementations

### Which chip

A file never names its chip, so `MfiChip` works it out. It tries these in order:

1. the `ainf` audio formats: `0x80` rohm, `0x81` fuetrek, `0x82` yamaha. This is certain, but
   only an mfi 4 or later with adpcm has one
2. a phone model in `supt` (`MFICONV_N505I`), looked up in `models.csv`
3. a Yamaha part in `supt` (`MA3Plugin_N`, `SCP-MA5-N-Plugin`)
4. the maker, together with the mfi version (`vers`). The maker comes from the vendor letter
   in `supt` (`SH_PlugIn`, `MFi4PlugIn_F`, `AT531_N`) or from the vendor nibble of the machine
   dependent messages: `0x10` N, `0x20` F, `0x30` SO, `0x40` P, `0x60` D, `0x70` SH
5. the file name, when `Condition.create(sequence, file)` is given one: a content provider
   names the files of a song for each maker and polyphony, `..._n40.mld`, `..._sh40.mld`,
   `..._p16.mld`. The letters are the maker, the polyphony the generation: 16 voices are the
   504i / 251i (mfi 2), 40 voices the 505i / 252i (mfi 3). A `vers` in the file wins over it
6. `mdplayer.mfi.chip.default` (`YAMAHA`, `FUETREK`, `ROHM` or `random`)

`models.csv` and the version rules come from the "MFi" sheet of the phone database.
When a maker changed chips within one mfi generation, the later phones win.

What a corpus says about it:

| corpus                          | files | told by                                                      |
|---------------------------------|------:|--------------------------------------------------------------|
| `~/Public/np2/mfi` (commercial) |  4433 | half have a `supt`, most of the rest have a vendor nibble    |
| `UnGoodMLD` (hobbyists, MLDC)   | 15462 | nothing: no `supt`, no `ainf`, no machine dependent messages |

The folders named after phones come out as their chips: `F901iC` all rohm, `P902i` 21 of 22
fuetrek, `N506iS` 91 of 92 yamaha (`MldDriverTest`). A file records the phone it was made for,
not the one it was found on: the `F-09A` folder is mostly `SH` data.

Of the first 6000 files of `~/Public/np2/mfi` named so, 3114 which told nothing else now come out
by the name (`d40` `p40` 48A fuetrek, `p16` `f16` rohm, `sh40` rohm, `so16` yamaha). Of those which
do tell, the name agrees for 2856, and 30 have a vendor nibble of another maker than the name.
