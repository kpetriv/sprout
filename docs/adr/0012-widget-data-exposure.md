# ADR-0012: Widget data exposure

- **Status:** Accepted
- **Date:** 2026-11-04
- **Milestone:** Decided and implemented in M7 (`widget/` `SproutWidget`; `app` `SessionWidgetStateSource`, `WidgetEntryPoint`, `GlanceWidgetRefresher`, `WidgetDataObserver`, `WidgetRefreshWorker`, `SproutWidgetReceiver`; `core/database` `KidTodaySummaryUseCase`)
- **Related:** [ADR-0003](0003-security-model.md) (security model), [ADR-0007](0007-identity-linking-and-device-registry.md) (linked kid devices, removal and wipe), [threat model](../THREAT_MODEL.md) T15 and T39, [M7 spec](../milestones/M7.md) §4, decisions D-M7-1, D-M7-6 and D-M7-7

## Context

The app keeps household data behind `FLAG_SECURE` windows: the parent area, kid mode and onboarding (D-M7-6, structural since M7). A home screen widget is drawn by the launcher, outside any of those windows: anyone who can see the phone's home screen sees it, screenshots and screen recordings capture it, and the launcher keeps the last drawing even while the app is not running. Phones in a Sprout household are of three kinds: a parent's phone (sometimes handed to a kid in kid mode), a kid's own linked phone (a KID session, no parent area at all), and phones with no session, the trial household, or a removal or "Sign in again" pending.

A widget is useful mostly to a kid: "how much do I have, what do I still have to do today". A parent's phone could show every kid, but it is also the phone a kid borrows, and the parent area is locked precisely so that a kid holding it does not see or change parent data.

## Decision

1. **Kid data only on that kid's own phone.** The widget shows a kid's first name, money balance (formatted for the kid's tier, so Seedling sees whole coins), screen-time minutes and the chores still to do today (the count and the first two titles in the 4×2 size) only when the phone's session is a KID session, and only for that session's own kid: `SessionWidgetStateSource` reads `KidTodaySummaryUseCase` for `session.kidId` and nothing else. No chat content, no ledger history, no other kid.
2. **Everywhere else it is a shortcut.** A parent's phone (unlocked or not, in kid mode or not), a phone with no session and the trial household show "Open Sprout". A parent opt-in widget with the kids' balances was not built.
3. **Blank while the household data is going away.** While a removal is stored (wiping or wiped), while a real session waits behind "Sign in again", when startup failed, or when the data cannot be read (a database closed by a wipe), the widget shows only its background. The removal's wipe redraws it, so the last kid drawing does not stay on a phone that left the household.
4. **The precedence is the app's own.** `widgetTargetOf` follows `appModeOf`: removal, then the gate, then the session role. The widget waits for the process-start work, so a removal found at start-up is never drawn over.
5. **Refresh without polling the database.** Glance collects the state while the widget's session runs; outside it, `GlanceWidgetRefresher` (conflated, debounced 500 ms) is asked after every successful sync, on Room invalidations of the tables the summary reads (`ledger_events`, `balances`, `accounts`, `kids`, `chores`, `chore_submissions`), after a time bank stop and after a removal's wipe, and a 6-hour `WidgetRefreshWorker` covers midnight and phones that never sync. The periodic work exists while a widget is placed and is cancelled with the household's other work on a removal.

## Consequences

- A kid's own home screen shows their own balance and chore titles to anyone looking at that phone. That is the point of the widget and the kid's own data; the parent can remove the widget like any other.
- A parent who wants balances at a glance opens the app; the dashboard is one PIN or fingerprint away.
- Kid names and chore titles never reach logs: `WidgetState.Kid` and `KidTodaySummary` print counts only, and nothing in the widget path logs.
- The widget lives in its own module with no Hilt and no `core/*` dependency: the app hands it a `WidgetStateSource` through `WidgetEntryPoint`, so the rule above lives in one tested function in the app.
