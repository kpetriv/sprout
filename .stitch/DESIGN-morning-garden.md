---
name: Sprout Morning Garden
colors:
  primary: '#2A6B45'
  on-primary: '#FFFFFF'
  primary-container: '#E9F3EB'
  on-primary-container: '#1E4B33'
  secondary: '#C4922A'
  on-secondary: '#FFFFFF'
  secondary-container: '#FDF4E2'
  on-secondary-container: '#735C00'
  tertiary: '#39697A'
  on-tertiary: '#FFFFFF'
  tertiary-container: '#EBF3F6'
  on-tertiary-container: '#264A61'
  background: '#FAF8F3'
  on-background: '#1A1C19'
  surface: '#FAF8F3'
  on-surface: '#1A1C19'
  on-surface-variant: '#5A635C'
  surface-container-lowest: '#FFFFFF'
  surface-container-low: '#F2F1EA'
  surface-container: '#E8EAE2'
  surface-container-high: '#E0E3D9'
  outline: '#7A837C'
  outline-variant: '#E3E6DC'
  error: '#BA1A1A'
  on-error: '#FFFFFF'
  error-container: '#FFDAD6'
  on-error-container: '#93000A'
typography:
  display-lg:
    fontFamily: Outfit
    fontSize: 42px
    fontWeight: '800'
    lineHeight: 48px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Outfit
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 36px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Outfit
    fontSize: 22px
    fontWeight: '700'
    lineHeight: 28px
  headline-sm:
    fontFamily: Outfit
    fontSize: 18px
    fontWeight: '700'
    lineHeight: 24px
  numeric-md:
    fontFamily: Outfit
    fontSize: 16px
    fontWeight: '700'
    lineHeight: 22px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 15px
    fontWeight: '500'
    lineHeight: 22px
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
  label-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '700'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.02em
rounded:
  sm: 8px
  md: 18px
  lg: 24px
  xl: 28px
  full: 9999px
spacing:
  space-xs: 4px
  space-sm: 8px
  space-md: 16px
  space-lg: 24px
  space-xl: 32px
  gutter: 20px
---

# Design System: Sprout Morning Garden

Sprout is an Android family pocket-money app: parents run chores, allowances, savings goals and screen time on a ledger they can trust; each child uses the same app in a layout that grows with them. "Morning garden" is the refined Sunny-garden direction Kiril chose on 2026-11-11 from Stitch renders (kid wallet "Refined Take 1", the in-place "Refined garden" edit, and the "Botanical Linen" parent dashboard). The values here are taken from those three renders.

## Atmosphere

A bright, tidy kitchen garden in the morning: a warm cream canvas, white cards with faint hairline borders, one deep botanical green, and three soft tints (leaf, honey, sky) used sparingly. Playful through tinted icon circles, pills and generous rounded cards, never through loud colour blocks. Flat: no gradients, no drop shadows, no emoji. Icons are Material Symbols Outlined at weight 500, filled only for the active state. Everything sits on an 8px grid with 20px side gutters.

## Colour

- **Cream** (#FAF8F3): page background. **Linen** (#F2F1EA): chips, tertiary buttons, tiles. **Moss grey** (#E8EAE2): progress tracks. **White** (#FFFFFF): every card, with a **Hairline** border (#E3E6DC).
- **Ink** (#1A1C19): text and icons. **Ink soft** (#5A635C): secondary text. **Outline** (#7A837C): captions, "Target £40.00".
- **Botanical** (#2A6B45): primary. Filled buttons, credits, progress fill, the sprout mark, selected states; pressed is **Botanical deep** (#1E4B33). **Leaf tint** (#E9F3EB) with Botanical-deep text for money heroes, credit icon circles, selected chips.
- **Honey** (#C4922A): secondary, for the sun, coin counts and attention numbers (large text and icons only). **Honey tint** (#FDF4E2) with **Honey deep** (#735C00) text for the screen-time badge, the Shop pill and attention circles; honey border #F0E0BC.
- **Sky** (#39697A): tertiary, for minutes and time deltas (use #3F82B4 only at 16px bold or larger). **Sky tint** (#EBF3F6) with **Sky deep** (#264A61) text for goal icons and time circles.
- **Error** (#BA1A1A) with **Error tint** (#FFDAD6): destructive actions, ledger problems, debit icon circles in the shop.
- Dark theme: background #141A16, cards #1C241E, Linen #222B25, Moss #2A342D, Hairline #3B463E, text #E4E7E0, Ink soft #AEB7B0; Botanical #8FD3A3 on #1A3A28, Leaf tint #1F4A30 with #C8F2C0 text; Honey #F0C56A, Honey tint #4A3A10 with #FDE7B8 text; Sky #8EC3EA, Sky tint #1C3D55 with #D5E8F5 text; Error #FFB4AB.

Text on any tint keeps 4.5:1; state is never colour alone.

## The colour dial by age

- **Seedling (5 to 7):** full garden: Leaf, Honey and Sky tints as large blocks, pictorial pieces (coin jar, sun, five stars), one or two giant pills, 28px corners, type 1.3x, 72px touch targets, pictogram icons larger than the text.
- **Sprout (8 to 11):** tints only where they mean something (Leaf-tint money hero text, Honey screen-time badge and Shop pill, Sky goal icon); white cards on cream for the rest. 24px corners, type 1.15x, 56px targets.
- **Sapling (12+) and parents:** cream and white, Botanical as the only accent, Honey and Sky for small figures and dots. 18px corners, standard type, 48px targets. Parents additionally use Honey-tint number circles for attention rows.

## Typography

- **Outfit** for titles, screen names and every number that matters: 800 for hero amounts (42px), 700 for headlines (28 / 22 / 18px) and row amounts (16px). Tight tracking, tabular figures.
- **Plus Jakarta Sans** for body, labels, buttons and rows: 500 body (15 / 13px), 600 body-lg (16px), 700 labels and button text (14 / 12px). Sentence case; the only uppercase is a 10 to 12px label such as "YOUR MONEY".

## Components

- **Buttons:** pills; primary Botanical fill with white text; secondary Honey tint with Honey-deep text and a honey border; tertiary Linen fill with Ink text and a hairline border. Never pure black. Height 48 / 56 / 64 / 72px by tier; icon left, label right.
- **Cards:** white, hairline border, 18 / 24 / 28px corners by tier, 20px padding, no shadow. One idea per card.
- **Hero (kid wallet):** "YOUR MONEY" label, 42px Outfit amount, a tier chip on Leaf tint, and a Honey-tint screen-time badge with a sun icon.
- **Ledger rows:** 56px, a 40px tinted icon circle (Leaf for credits, Sky for time, Honey for rewards, Error tint for spending, Linen for transfers), name in body-md 700, detail in body-sm Ink soft, amount right in numeric-md (Botanical credit, Sky minutes, Ink debit). Rows in one white card with hairline dividers, no chevrons.
- **Goal card:** icon circle on Sky tint, title, "£27.50 to go" caption, a "31% saved" chip on Leaf tint, a 14px rounded track in Moss grey with Botanical fill, "£12.50 saved" in Botanical and "Target £40.00" in Outline.
- **Parent attention rows:** one white card listing three rows, each a Honey-tint circle with the count in Outfit, title and detail, and a pill action on the right (Botanical for "Sign now", Linen for "Review").
- **Kid cards (parent):** avatar circle with the initial on a per-kid tint (Leaf, Honey, Linen), name and age, Linen stat chips with a small Botanical icon, a Linen goal panel with a thin track.
- **Chips:** pills on Linen with hairline; selected on Leaf tint with Botanical-deep text.
- **Top app bar:** cream, back arrow, Outfit title, at most one trailing icon. No bottom navigation bar in Sprout.
- **Motion:** buttons scale to 0.96 on press; confetti in Botanical, Honey and Sky; reduced motion honoured.

## Never

No emoji, gradients, shadows, pure black, neon or more than three accent tints on one screen; no decorative illustration on the parent side; no all-caps headlines; no fabricated data (Mia 6, Leo 9, Ava 13; £7.45, 1 h 20 min, "Saving for a new bike" £12.50 of £40.00).
