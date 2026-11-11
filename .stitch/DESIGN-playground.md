---
name: Sprout Playground (games)
colors:
  field: '#1E4B33'
  field-deep: '#133322'
  field-panel: '#27583F'
  chalk: '#FFFFFF'
  chalk-soft: '#FFFFFF99'
  ink: '#1A1C19'
  lime: '#B8E986'
  lime-edge: '#8BC950'
  marigold: '#F5B52A'
  marigold-edge: '#C48A14'
  sky: '#7CC4EE'
  sky-edge: '#4A98C7'
  coral: '#FF8A65'
  coral-edge: '#D1603E'
typography:
  display-xl:
    fontFamily: Outfit
    fontSize: 68px
    fontWeight: '900'
    lineHeight: 72px
    letterSpacing: -0.02em
  display-lg:
    fontFamily: Outfit
    fontSize: 42px
    fontWeight: '800'
    lineHeight: 48px
  headline-md:
    fontFamily: Outfit
    fontSize: 24px
    fontWeight: '800'
    lineHeight: 30px
  tile-lg:
    fontFamily: Outfit
    fontSize: 32px
    fontWeight: '900'
    lineHeight: 36px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 15px
    fontWeight: '600'
    lineHeight: 22px
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.04em
rounded:
  tile: 20px
  tile-seedling: 24px
  tile-sapling: 16px
  panel: 24px
  full: 9999px
spacing:
  edge: 4px
  edge-sapling: 3px
  gutter: 24px
  tile-gap: 14px
---

# Design System: Sprout Playground (in-game screens)

The character of Sprout's brain games from the start card to the result, chosen by Kiril on 2026-11-11 from Stitch renders ("B, Playground") over the Morning garden re-skin ("A"). It is Morning garden turned inside out: the pressed Botanical green (#1E4B33, Morning garden's `primary` pressed state) becomes a full-bleed play field, the same two typefaces (Outfit, Plus Jakarta Sans) and the same Ink are kept, and four flat game accents replace the three soft tints. Everything outside the game (the games hub, the wallet, the rest of the app) stays Morning garden.

## Atmosphere

A chalkboard playground: deep green edge to edge, white type, big chunky tiles that look pressable because of a solid darker bottom edge (a flat offset, never a blurred shadow). Playful through colour blocks and size, never through gradients, emoji or illustration. Icons are Material Symbols Outlined at weight 600, flat geometric shapes stand in for the memory symbols.

## Colour

- **Field** (#1E4B33): the page. **Field deep** (#133322): the inside of rings and the top bar tiles. **Field panel** (#27583F, or white at 10%): sequence panels, answer boxes, history rows.
- **Chalk** (#FFFFFF) for text and outlines; **Chalk soft** (white at 60%) for captions. **Ink** (#1A1C19) for text on a coloured tile.
- **Lime** (#B8E986, edge #8BC950): progress fill, streak pill, the reward card, "Perfect!", selected level.
- **Marigold** (#F5B52A, edge #C48A14): answer tiles, selected tables, the one primary action ("Done", "Start", "Go", "Check"), the result rosette.
- **Sky** (#7CC4EE, edge #4A98C7): the countdown ring, placed answers (slots), the number pad, read-aloud tiles.
- **Coral** (#FF8A65, edge #D1603E): the backspace tile, one of the four memory shapes, confetti.
- Memory shapes map the symbol ids to these accents: red → Coral, green → Lime, blue → Sky, yellow → Marigold; star, heart, triangle, diamond are drawn in Chalk or Sky.

Text on every tile keeps 4.5:1 (Ink on Marigold 9.4:1, Ink on Lime 11.8:1, Ink on Sky 9.1:1, Chalk on Field 9.6:1, Lime on Field 7.2:1, Sky on Field 6.1:1). State is never colour alone: a used letter tile dims and loses its edge, a disabled shape tile dims to 40%, a selected level carries a check.

## The colour dial by tier

- **Seedling (5 to 7):** all four accents, tiles 112px tall with 24px corners and 4px edges, text never below 18px, a Sky read-aloud tile beside every heading, at most four choices, nothing decorative beyond the confetti on the result.
- **Sprout (8 to 11):** all four accents, tiles 72px with 20px corners and 4px edges, a streak pill, the countdown ring only in timed Times tables.
- **Sapling (12+):** quiet Playground: Lime and Sky only, Marigold reserved for the single primary action, 56px tiles with 16px corners and 3px edges, number pad and typed answers, a "Your last plays" list on the start card. The field stays green: the game is still a game.

## Components

- **Play tile:** a rounded rectangle filled with an accent, a solid bottom edge in the accent's edge colour; pressed, it translates down by the edge height and the edge shrinks to 1px (an 80 ms tween; reduced motion skips the translate). Outline variant: 2px Chalk at 60% on the Field, no edge.
- **Segmented progress:** one fat rounded segment per round (14px tall at Seedling and Sprout, 8px at Sapling), Lime when done, white at 15% otherwise; "3 of 8" in Outfit at the right.
- **Countdown ring:** 48px circle, 4px Sky stroke that sweeps away over the round's limit, the seconds left in Outfit inside; informational only (a slow answer loses the bonus, nothing else).
- **Sequence panel:** Field-panel rounded box with one 52 to 80px slot per symbol; filled slots show the shape on its accent with a small index badge, empty slots are dashed Chalk outlines.
- **Reward card:** Lime tile with "Game reward" label, the amount huge in Ink, the cap line beneath; the Seedling version holds the minor-unit words only ("+8 pennies").
- **Rosette:** a 120px Marigold flower of eight flat circles with a white star; replaced by a plain Lime star for a non-perfect play.
- **Streak pill:** Lime outline pill with a flat flame icon and "Streak 3".
- **Number pad:** 3 by 4 grid of Sky tiles (Sapling: Field outlines), Coral backspace, Marigold "Go".
- **Top bar:** transparent on the Field, a back tile (white at 12%), the game name in Outfit 800, the sprout mark in Lime at the right.

## Never

No gradients, no blurred shadows, no emoji, no pure black, no illustration, no scores or coins invented beyond what the ledger pays (at most 15 minor units a play), no "level" or "age" chips, no footer that promises a reward for finishing.
