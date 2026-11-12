# Stitch prompts used for M9

Project `10251972321848422943` ("Sprout"). Device MOBILE unless noted. Prompts that produced the direction renders are summarised in `docs/milestones/M9.md` §1; the three refinement prompts below were accepted by Stitch on 2026-11-11 but no screen landed (see M9.md §9). Re-run them as they are.

## Refined take 1 (morning garden)

Refined "Sunny garden" take 1: kid wallet for a 9-year-old in Sprout, an Android family pocket-money app. Mobile 390px, portrait, light. Playful and warm but slick and polished. You choose palette and type: green is the brand colour; add a harmonious set of soft morning-garden tints (dew, sunlight, sky), not primary-school primaries. Characterful display face for numbers and titles, clean readable sans for the rest (not Nunito, not Inter). One card style, rounded icons in tinted circles, consistent radius, generous type, 56px touch targets, no shadows, gradients or emoji.

CONTENT: top bar: back arrow, "Leo's wallet". Hero: "Your money", big "£7.45", screen time "1 h 20 min" with a sun icon. Pill buttons "Chores" (primary) and "Shop". Goal card "Saving for a new bike", bar 31%, "£12.50" left, "£40.00" right. "Recent": "Chore reward +£1.50", "Shop −£3.99", "Screen time used −30 min", "Pocket money +£5.00", "Moved to savings £2.00", "Interest +£0.12", "Game reward +£0.20"; credits green, minutes in the sky tint.

## Refined take 2 (orchard)

Same content. Style: green on a warm cream canvas with two muted orchard accents (apricot and plum, or honey and clay), grown-up rather than loud; characterful geometric display face, clean humanist sans; one soft-tinted card style.

## Refined take 3 (bold hero)

Same content. Style: deep garden green as a full-bleed hero block with light text, one bright refined accent (sunny gold or coral) for the single most important action, everything else near-white with soft green-grey neutrals; rounded-but-grown-up display face.

## Parent dashboard, refined

Same content as `B-dashboard.png` (Needs you: Chores to approve 4, Payments to sign 3 with "2 allowances, 1 interest" and Sign now, Ledger check 1; Kids Mia 6 / Leo 9 / Ava 13 with their figures and Leo's bike goal; This week paragraph with "Written on this phone"). The parent side is the restrained end of the family: mostly neutral surfaces, colour only where something needs the parent. No bottom navigation bar.

## Showcase screens with the Morning Garden design system (run 2026-11-11, ~18:40 UTC)

All with `designSystem: assets/87c1dbeea6a3488c891bc866774156aa`, device MOBILE, no colours or fonts in the prompt. Each starts with the tier's colour level and sizes, then exact content:

- **Wallet Seedling (Mia, 6):** "Mia's wallet"; "Hi Mia!"; flat coin jar with 7 coins, "7 coins"; flat sun, "1 h 20 min"; "Saving for a new bike" as five stars, one filled; giant pills "Chores" (primary, broom) and "Shop" (honey, bag); wide "Read to me" pill.
- **Wallet Sapling (Ava, 13):** "Ava's wallet"; Accounts tiles Money £7.45 / Screen time 1 h 20 min / Savings £12.50; pills Chores, Shop; goal card 31%; Interest card "You've earned £0.12 interest so far."; Budget card "coming soon"; "Export CSV"; History chips All, Chores, Pocket money, Games, Shop; dated rows 25 to 19 Oct 2026.
- **Chores Sprout (Leo):** "Leo's chores"; Today; Make your bed £0.50; Brush teeth 10 min; Water the plants £1.50 + 15 min; "I did it" on each.
- **Chores Seedling (Mia):** tinted cards, pictograms bigger than text, "2 coins" / "10 min" / "3 coins", "Read to me".
- **Chores Sapling (Ava):** compact rows with due times, "Propose a chore", This week, Waiting for approval with a Pending chip.
- **Shop Seedling (Mia):** "You have 20 coins"; Sticker pack 2, 30 min tablet time 3, Ice cream 5 ("You can buy this now"), Cinema trip 15 ("Keep saving").
- **Chat parent thread with Leo:** four messages as in `05-chat.png`, photo bubble, composer.
- **Games hub Sprout:** banner "You can still earn £0.23 today"; Numbers, Memory, Word scramble.
- **Time bank Seedling running:** ring 70%, "33" minutes, Stop, "Time left today: 1 h 20 min".
- **Household data (parent settings):** Chat retention 90 days, Delete chat history now, Export household, Delete household.
- **Parent dashboard, final:** Botanical Linen layout with a sprout mark, no bottom navigation.
