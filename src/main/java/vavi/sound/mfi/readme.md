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
