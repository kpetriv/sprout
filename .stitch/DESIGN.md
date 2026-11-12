---
name: Sprout
colors:
  primary: '#2E7D32'
  on-primary: '#FFFFFF'
  primary-container: '#B7F0B1'
  on-primary-container: '#002106'
  secondary: '#F2A900'
  on-secondary: '#1A1C19'
  secondary-container: '#FFE08A'
  on-secondary-container: '#261A00'
  tertiary: '#00658E'
  on-tertiary: '#FFFFFF'
  background: '#F8FBF2'
  on-background: '#1A1C19'
  surface: '#F8FBF2'
  on-surface: '#1A1C19'
  surface-container: '#EEF2E8'
  surface-container-high: '#E4E9DE'
  outline: '#74796F'
  outline-variant: '#C4C9BC'
  error: '#BA1A1A'
  on-error: '#FFFFFF'
  surface-dark: '#1A1C19'
  on-surface-dark: '#E2E3DD'
  primary-dark: '#8BD88F'
  primary-container-dark: '#0F5223'
  secondary-dark: '#FFCF5C'
  secondary-container-dark: '#5C4300'
  tertiary-dark: '#8BCEFF'
typography:
  display-lg:
    fontFamily: Roboto
    fontSize: 57px
    fontWeight: '400'
    lineHeight: 64px
  headline-md:
    fontFamily: Roboto
    fontSize: 28px
    fontWeight: '400'
    lineHeight: 36px
  title-lg:
    fontFamily: Roboto
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 28px
  title-md:
    fontFamily: Roboto
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 24px
  body-lg:
    fontFamily: Roboto
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Roboto
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-lg:
    fontFamily: Roboto
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-sm:
    fontFamily: Roboto
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.04em
spacing:
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
  gutter: 1rem
  content-max-width: 640px
---

# Design System: Sprout

Sprout is an Android app for families: parents run pocket money, chores, savings goals and screen time on a tamper-evident ledger; each child uses the same app on their own phone, in a layout that grows with them. This file describes Sprout's identity as shipped in 1.0 (Material 3, brand palette, tier-scaled sizes). It is the starting point for the 1.1 visual redesign: directions explored in Stitch may replace the type, shapes and surfaces, but must keep the identity, the mark, the two roles and the three kid tiers.

## 1. Identity and atmosphere

- **Who it is for.** Two roles in one app. The **parent** side is a calm, trustworthy finance tool: balances, approvals, a ledger that can be audited, settings about privacy and data. The **kid** side is the child's own money and time, in three age tiers.
- **Mood.** Calm, honest, a little playful. Garden metaphors (leaf, sun, soil, paper) rather than bank metaphors. Nothing gamified for its own sake: coins, stars and jars are real quantities a child can count, never loot.
- **The mark.** A two-leaf sprout: a short stem with one leaf curving left and one curving right, drawn in solid Leaf green on a Leaf Light disc (the adaptive launcher icon). The mark appears alone; there is no wordmark beyond the app name set in the headline face.
- **Density.** Daily-app balanced on the parent side (one idea per card, lists with generous rows); airy on the kid side, airiest for Seedling.

## 2. Three kid tiers (the thing every direction must respect)

Kid screens take a tier and switch content and sizing on it. The parent side always uses Sapling sizes.

| Tier | Age | Type scale | Minimum touch target | Medium corner radius | Character |
|---|---|---|---|---|---|
| **Seedling** | about 5 to 7 | 1.3× | 72 dp | 28 dp | Pictures over words. A coin jar for money, a sun for screen time, stars for a goal, two or three giant buttons, "Read to me". Counts in coins, never decimals. |
| **Sprout** | about 8 to 11 | 1.15× | 56 dp | 20 dp | Real money (£7.45), a goal bar, a short "Recent" list with icons, due dates, a kindness check before sending a message. |
| **Sapling** | 12 and up | 1.0× | 48 dp | 12 dp | The full ledger with filter chips, interest explained, budgets, proposing chores, a wishlist, CSV export. Reads like the parent app, with the kid's own voice. |

Material's five shape slots scale from the tier's medium radius (0.25×, 0.5×, 1×, 1.5×, 2×). Type is the Material scale multiplied by the tier factor. Everything tappable is at least the tier's touch target; the accessibility rule in CI fails a screen that breaks it.

## 3. Colour palette and roles (as shipped)

Light theme on a warm paper background; a full dark theme on soil.

- **Leaf** (#2E7D32): primary. Filled buttons, the mark, progress, "I did it", the running timer ring.
- **Leaf Light** (#B7F0B1): primary container. Game tiles, chat bubbles sent by the parent, the store frames, "you can buy this" shop tiles.
- **Sun** (#F2A900) and **Sun Container** (#FFE08A): secondary. Attention cards on the parent dashboard ("Chores to approve", "Payments to sign"), the daily-credit banner in games, coins in the jar, the Seedling sun.
- **Sky** (#00658E): tertiary. Minutes and time deltas in the ledger, links.
- **Paper** (#F8FBF2): background and surface. Warm, never pure white.
- **Soil** (#1A1C19): text on paper; the dark theme's background.
- **Surface containers** (#EEF2E8, #E4E9DE): card fills, chat bubbles received, the ledger's account cards. In 1.0 these fall back to Material's lavender-tinted defaults, which clash with the garden palette; the redesign should give them a green-tinted neutral.
- **Error** (#BA1A1A): destructive actions ("Delete chat history now"), ledger problems.
- **Dark theme:** Leaf → #8BD88F, Leaf container → #0F5223, Sun → #FFCF5C, Sun container → #5C4300, Sky → #8BCEFF, text #E2E3DD on Soil.

Contrast is checked in tests (body text 4.5:1, large text 3:1) and state is never colour alone: shop tiles say "You can buy this now" or "Keep saving", chips carry their state as text.

## 4. Typography (as shipped)

Material 3's default type scale in Roboto, scaled per tier. No display face, no custom family yet. Numbers use the body face with tabular figures where amounts align in a list. Headline weight is regular; titles medium. The redesign may choose a brand face (headline and body, one or two families) but it must render on Android through Compose with downloadable or bundled fonts, support £ and the Latin and Cyrillic ranges, and stay legible at 1.3× for Seedling and at font scale 2.0 for accessibility.

## 5. Components (as shipped)

- **Cards:** filled, tier-rounded, no shadow; one card per idea on the parent dashboard with a label, one line of body and one pill button.
- **Buttons:** Material filled (Leaf on white text), tonal (Sun container) and outlined pills. Seedling buttons are big squares with a pictogram and a one-word label.
- **Ledger rows:** a circular tinted icon, the event name, an optional second line, and a signed amount on the right (green for credit, sky for minutes, default for debit).
- **Account cards:** label in small caps-sized text over a large amount.
- **Goal bar:** a thin Leaf progress bar with the saved and target amounts at the ends; stars for Seedling.
- **Coin jar and sun:** custom drawings for the Seedling wallet (`BalanceJar`, `MinutesSun`).
- **Timer ring:** a thick Leaf arc with tick marks around a solid disc showing minutes and Stop.
- **Chat:** bubbles with the sender's name above and the time below; Seedling gets preset messages and voice notes.
- **Icons:** 1.0 uses emoji for chore, shop and game pictograms and Material symbols for controls. The redesign should replace emoji with a consistent illustrated icon set (one weight, one palette) so screenshots render the same on every device and Robolectric.
- **Shapes:** Material slots scaled from the tier radius; the parent side is 12 dp medium.
- **Motion:** confetti on an approved chore or a bought reward; the timer ring animates; otherwise Material defaults. Reduced motion is honoured.

## 6. Layout

- Mobile first, portrait. Content width capped at 640 dp with gutters that scroll with the content; on tablets, list and detail side by side.
- Parent screens: top app bar with a back arrow and the screen title as a heading, then a scrolling column of cards or a lazy list.
- Kid screens: the kid's name in the title ("Leo's wallet"); Seedling centres everything and keeps to one screen without scrolling where it can.
- Dialogs and bottom sheets keep the tier's touch targets.

## 7. Constraints for any redesign direction

- Keep the two-leaf mark, the Leaf green as the brand anchor, the three tiers and their size tokens (type scale, touch target, radius may be re-tuned but stay monotonic Seedling > Sprout > Sapling).
- Keep light and dark themes; dynamic colour stays optional and off by default.
- Everything must map onto Compose Material 3 tokens (`ColorScheme`, `Typography`, `Shapes`) plus Sprout's own `TierTokens`, because the app is Compose-only and every screen has screenshot goldens per tier.
- Contrast 4.5:1 for body text; state never colour alone; touch targets per tier.
- No fabricated data in renders: use the demo household (parent, kids Mia age 6, Leo age 9, Ava age 13; amounts like £7.45, 1 h 20 min, a goal "Saving for a new bike" £12.50 of £40.00).
- No emoji in the parent UI; kid pictograms are illustrated icons, not emoji.
