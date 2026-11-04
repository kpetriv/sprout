# Architecture decision records

Each ADR records one significant decision in the short format described by Michael Nygard: **Status** (Proposed, Accepted, Superseded by ADR-NNNN), **Context** (the forces and constraints at the time), **Decision** (what we do, stated in the active voice), and **Consequences** (what becomes easier, what becomes harder, and what we have to watch). Sprout's ADRs also list the alternatives considered and why they were rejected. ADRs are immutable once accepted; a changed decision gets a new ADR that supersedes the old one, so the history of reasoning stays readable. A refinement that does not reverse the decision (a tightened invariant, a clarified encoding) may be appended to the ADR as a dated **Amendments** section; the original text stays as written.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-architecture.md) | Multi-module, offline-first architecture | Accepted |
| [0002](0002-ledger-as-events.md) | Ledger as a signed, append-only event stream | Accepted; amended 2026-11-02 (M1 review) and by ADR-0004 |
| [0003](0003-security-model.md) | Security model: identity, signing, and scoped E2EE | Accepted; parent key policy, PIN hashing, and device key provenance amended by ADR-0004 |
| [0004](0004-parent-authorisation-and-device-identity.md) | Parent authorisation of ledger events and device identity | Accepted (implemented in M2/M3); amended 2026-11-03 (M2 review: PIN-gated key enrolment and renewal, lockout cap, "Sign now") and 2026-11-03 (M3: one conflicting copy applies, registry roles, server-registered parent keys, held and forked events) |
| [0005](0005-scheduled-payments-and-money-flow-atomicity.md) | Scheduled payments and money-flow atomicity | Accepted (implemented in M2) |
| [0006](0006-sync-protocol-and-document-envelope.md) | Sync protocol and document envelope | Accepted (implemented in M3) |
| [0007](0007-identity-linking-and-device-registry.md) | Identity, device linking and the device registry | Accepted (implemented in M3) |
| [0008](0008-chat-encryption-and-thread-keys.md) | Chat encryption and thread keys | Accepted (implemented in M4) |
| [0009](0009-games-scoring-and-credit.md) | Games scoring and credit | Accepted (implemented in M5) |
| [0010](0010-on-device-ai.md) | On-device AI: gateway, router and validators | Accepted (implemented in M6) |

Related: [threat model](../THREAT_MODEL.md), [controls matrix](../CONTROLS.md), [incident runbook](../RUNBOOK.md), [Firebase backend](../FIREBASE.md), [privacy policy draft](../PRIVACY.md).
