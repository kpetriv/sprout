# ADR-0010: On-device AI

- **Status:** Accepted
- **Date:** 2026-11-04
- **Milestone:** Decided and implemented in M6 (`core/ai`, `core/domain-household` `WeeklyFacts`, `core/database` `WeeklyFactsUseCase`)
- **Related:** [ADR-0001](0001-architecture.md) (module layout), [ADR-0002](0002-ledger-as-events.md) (the ledger the facts come from), [M6 spec](../milestones/M6.md), [privacy policy draft](../PRIVACY.md), decisions D-M6-1 to D-M6-32, threat model T9 and T38

## Context

Sprout has four jobs where a language model helps: a kindness nudge before a kid sends a chat message (M4 shipped the hook with no filter behind it), chore ideas for a parent, a short weekly summary on the parent dashboard, and reading a receipt photo to prefill a manual adjustment. The product promise (PLAN.md, PRIVACY.md) is that nothing leaves the device for AI.

The only on-device model Android offers apps is Gemini Nano, through AICore and the ML Kit GenAI APIs. Its constraints shape everything here:

- **Not everywhere.** Only some recent phones have AICore and Nano; the emulator never does. The model is downloaded by the system, on Wi-Fi, after an app asks.
- **Not always.** AICore serves only the foreground app, has a per-app quota, and answers `BUSY` when either is hit. Latency varies by device and load.
- **Beta.** The Prompt API (`com.google.mlkit:genai-prompt`) is beta; its structured output (a KSP-generated schema from `@Generable` classes) is alpha.
- **Untrusted output.** A model can be wrong, can be talked into things by its input, and can invent numbers. Kid and parent text is exactly the input it sees.

## Decision

### 1. One gateway, rules first (D-M6-1)

`core/ai` exposes one `AiGateway` with four typed tasks (`kindness`, `suggestChores`, `weeklySummary`, `receiptPrefill`) plus `status` and `requestDownload`. There are two implementations:

- **`RulesAiGateway`** is pure, deterministic Kotlin and runs on every device: a kindness lexicon we wrote (insults, swearing, hostile phrases, "you are + negative word", shouting), fixed kinder suggestions per category and tier, a curated chore catalogue (at least 12 per age band, D-M6-4), a weekly summary template, and a receipt parser over on-device text recognition. It is the reference behaviour, and its results are what users get on most devices.
- **`GeminiNanoAiGateway`** runs the three text tasks on Gemini Nano through a small `PromptModel` seam (`MlKitPromptModel` on device, a fake in JVM tests). Receipts never use the model.

**Gemini Nano is an optimisation of the rules path, never a dependency.** `AiRouter` (what the app injects) uses Nano only when the parent's switch is on (`AiPreferences.enabled`, default on) and `status()` is `AVAILABLE`; everything else, and every failure, gives the rules result. One budget covers the status check and the call: 2 s for kindness (the chat feature fails open after its own 2.5 s), 6 s for suggestions and summaries (D-M6-10). The router never throws for AICore or settings failures.

### 2. The model only proposes; validators decide

Every model answer is parsed through structured output (one `@Generable` class per task) and then checked by `AiOutputRules`; anything that fails is **replaced** by the rules result, never repaired:

- **Kindness:** a message is nudged when the rules *or* the model find it unkind (D-M6-12). The model's suggestion is used only if it is clean (at most 200 code points, single line, no link, e-mail, phone number, instruction-like phrase or control character), passes the rules check itself, is not the original again and repeats none of the words the rules flagged. A nudge never blocks; nothing is reported to anyone (M4).
- **Chore suggestions:** titles at most `MAX_TITLE_LENGTH`, descriptions at most `MAX_DESCRIPTION_LENGTH`, both clean and kind; money in (0, min(3 × the band's largest catalogue reward, `LedgerPolicy.maxAmount`)]; minutes 1 to 120; no title equal (ignoring case, spacing and punctuation) to an existing chore or another suggestion; three to five survivors or the catalogue answer. Filling the editor never saves.
- **Weekly summary (D-M6-3):** the facts are computed deterministically (`WeeklyFactsUseCase`, below); the model only rewrites the template's draft. The text must be clean, 2 to 4 sentences, at most 600 code points, and **every number in it must be one of the facts** (digits read with either decimal convention, and spelled-out numbers from "two" up). An invented amount puts the template text on screen.

### 3. Prompt-injection stance

Kid and parent text (a chat message, existing chore titles, kid names in the facts) is **data, never instructions**:

- fixed instructions go in the system instruction (or, where the device's model has none, before the user turn); user text appears only inside JSON string literals after a label, with `<`, `>`, `{`, `}`, backticks and control or format characters escaped, so it cannot close its string, open a role tag or look like markup;
- the instructions say so, and ask for the structured fields only, at a low temperature;
- whatever comes back is bounded by the validators above. The worst a successful injection can do is make a suggestion or summary that still passes them: kind, short, link-free, and with numbers from the facts. No AI output reaches the ledger: chore suggestions fill an editor the parent saves; a receipt prefill (text recognition and rules, no model) fills an adjustment the parent reviews and signs, and the merchant becomes plaintext only then (D-M6-5).

### 4. Nothing persisted, nothing logged (D-M6-7)

Suggestions, verdicts and summaries are recomputed when shown and never stored. Result types redact text in `toString`. Logging records only the task, the path (nano or rules), a latency bucket and a failure kind, at DEBUG through Timber, so release builds keep nothing.

### 5. Download with consent (D-M6-6)

`requestDownload` reaches AICore only after the parent's one-time consent in Settings (`AiPreferences.downloadConsent`, default off); the router refuses it otherwise. AICore downloads on Wi-Fi in the background. The two settings live in a device-local DataStore file (`ai_preferences`) and are not synced: each device has its own model.

### 6. Weekly facts

`WeeklyFacts` (pure, `core/domain-household`) holds per kid: money earned by kind (chores, allowance, games, interest), spent on rewards, moved to savings, screen-time minutes earned from chores and used, chores marked done, approved and sent back, games played and ledger anomalies; `totals` sums the household. `WeeklyFactsUseCase` (`core/database`) folds the stored events the projection **applied** (invalid events, excluded games and losing conflict copies are left out) and the chore submissions, each by the household-local date of its timestamp (`HouseholdCalendar.dateOf`): the event's `createdAt`, a submission's `submittedAt`, a decision's `decidedAt`. The current ISO week runs to today; any past week is whole. No schema change (D-M6-16).

## Alternatives considered

- **Per-feature ML Kit APIs** (Summarization, Rewriting, Proofreading): three dependencies, three result shapes, no structured output and no way to enforce the summary's numbers. Rejected for the Prompt API (D-M6-8).
- **Cloud models** (Gemini API, Firebase AI Logic): better answers everywhere, but kid chat text and household finances would leave the device. Out of scope by product promise.
- **Model-only features** (no rules path): most devices, the emulator and every CI run would have no feature at all, and each failure would need its own UI. Rejected; the rules path is the product and Nano improves wording where present.
- **Trusting structured output alone:** the schema constrains shape, not content (an invented number, a link, an insult in a "kind" suggestion all fit the schema). Rejected; the validators are mandatory.

## Consequences

- The app behaves the same on every device; Nano changes wording, not what a feature does. The Nano path is unverified on hardware until a run on a supported Pixel (D-M6-9); the JVM tests cover it through the `PromptModel` fake.
- The rules lexicon is English and deliberately narrow (it targets insults at the reader, not negative words in general), so it misses some unkind messages and some spellings ("i h8 u"). A miss costs a nudge that did not happen; a false nudge costs trust in nudges. Both are accepted; the lexicon is ours to extend.
- AICore is a Google system service running on the device. Text given to it is as sensitive as what the user typed, and is subject to AICore's own handling (PRIVACY.md, THREAT_MODEL T38).
- Structured output is alpha and needs the KSP schema compiler plus R8 keep rules (`core/ai/consumer-rules.pro`); an API change there is contained in `MlKitPromptModel` and `NanoOutputs.kt`.
- Model budgets are wall-clock limits from a ViewModel; a summary or suggestion request can take up to 6 s before the rules answer appears. Callers show progress.
