# Pending decisions for Kiril

Decisions taken with a default during the autonomous run so work could continue. Each one can be reversed; the default is what the code does now. Review, then delete rows that are confirmed and open an ADR for anything that changes.

| Id | Decision needed | Default applied | Where |
|---|---|---|---|
| D-M1-1 | Conflicting copies of one eventId: should a parent-authored copy always win over kid copies? | Yes, parent-wins; conflict still reported with the kid's device named. Both excluded only when the copies share a role. | `core/domain-ledger` `appliedCopyOf`, ADR-0004 |
| D-M1-2 | Should a rejected local append be written to the audit log? | No, nothing is written on rejection. | `RoomLedgerRepository.append` |
| D-M1-3 | SQLCipher 4.20.0 (published 2026-10-27) vs 4.19.1 | 4.20.0, checksums verified against Maven Central. | `gradle/libs.versions.toml` |
| D-M2-1 | Devices without a strong biometric: parent-only events cannot carry `parentSignature`. Accept unsigned parent events for that household (visible warning in settings) or block parent money actions on such devices? | Accept, with `parentSignatureRequired = false` on the household policy and a settings warning. Device credential is never used for signing (ADR-0004). | `core/crypto` `ParentAuthAvailability`, `feature/settings` |
| D-M2-2 | Batch approvals: one biometric prompt per approved chore (ADR-0004 timeout 0) or a short validity window (e.g. 60 s) that covers a batch? | One prompt per event, as ADR-0004 states. The approvals inbox approves one submission at a time. | `feature/chores` approvals |
| D-M2-3 | Chore photos: store locally only (M2) and upload encrypted in M4, or skip photos until M4? | Local only, encrypted at rest with a per-install AEAD key; the household key replaces it in M4. | `core/database` `photos` |
| D-M2-4 | Allowance catch-up after a long offline gap: pay every missed period or cap? | Cap at 8 periods; older ones are skipped and listed in the parent dashboard. | `AllowancePlanner` |
| D-M2-5 | Interest accrual: monthly on a fixed day (default 1st) on the SAVINGS balance, simple interest Actual/365, default rate 0 bps (off) until the parent sets one. | As described. | `InterestPolicy` |
