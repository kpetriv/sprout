# Bundled fonts (M9, D-M9-4)

Static TrueType instances bundled as `src/main/res/font`, so text renders offline and identically in Robolectric.
Both families are under the SIL Open Font License 1.1; the licence texts sit next to this file.

| Resource | Family, weight | Source file | Bytes | SHA-256 |
|---|---|---|---|---|
| `outfit_bold.ttf` | Outfit 700 | `fonts/ttf/Outfit-Bold.ttf` | 75 992 | `f620b69582e06d7e1b3bbde74ed8c5876eadabb038390780db2a3414a1490197` |
| `outfit_extrabold.ttf` | Outfit 800 | `fonts/ttf/Outfit-ExtraBold.ttf` | 74 048 | `0f028cbdc61a588bc44fef911e8d2bcfc0bc05b241a9b797686024d269d964b6` |
| `plus_jakarta_sans_medium.ttf` | Plus Jakarta Sans 500 | `fonts/ttf/PlusJakartaSans-Medium.ttf` | 129 180 | `c77bab757d7402ec6d9341d5f7ddaafb2474e17026792697ba4624c7dc89caf7` |
| `plus_jakarta_sans_semibold.ttf` | Plus Jakarta Sans 600 | `fonts/ttf/PlusJakartaSans-SemiBold.ttf` | 129 288 | `65dbcedb6596a41c30869729eb31cb57d1f5edfe365684314bba8a1994eaa4cb` |
| `plus_jakarta_sans_bold.ttf` | Plus Jakarta Sans 700 | `fonts/ttf/PlusJakartaSans-Bold.ttf` | 128 988 | `5f5342ef76862b5b5365d1dff1a667629dfa484e388dd602552f647219c3870f` |

Sources (fetched 2026-11-11 from `raw.githubusercontent.com` at these commits, which are also the commits the
google/fonts `METADATA.pb` files pin):

- Outfit: https://github.com/Outfitio/Outfit-Fonts, commit `902773808eb372f70fb34e8946dd1ffe604efc79`
  (latest release tag `1.1`). Licence: `OFL-Outfit.txt`.
- Plus Jakarta Sans: https://github.com/tokotype/PlusJakartaSans, commit `18d1cd2f7ea10481919d2f05c1f7064b7307fc26`
  (latest release tag `2.7.1`). Licence: `OFL-PlusJakartaSans.txt`.

## Coverage

Read from each file's `cmap` table:

- Outfit: 360 code points, Latin and Latin Extended-A (`£`, `€`, `ĄŁŻčšž`). No Cyrillic.
- Plus Jakarta Sans: 721 code points, Latin, Latin Extended and Vietnamese. No Cyrillic (U+0400 to U+04FF), in the
  static instances and in google/fonts' variable file alike.
- Both carry the OpenType `tnum` feature (tabular figures), which the display and headline styles switch on.

Cyrillic text therefore renders through Android's system fallback (Roboto / Noto), not through the bundled faces.
`FontGlyphTest` checks the bundled files' own coverage for `£`, `€` and the Latin Extended sample, and checks that
Cyrillic still resolves to a glyph through the fallback chain.
