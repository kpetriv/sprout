# Architecture decision records

Each ADR records one significant decision in the short format described by Michael Nygard: **Status** (Proposed, Accepted, Superseded by ADR-NNNN), **Context** (the forces and constraints at the time), **Decision** (what we do, stated in the active voice), and **Consequences** (what becomes easier, what becomes harder, and what we have to watch). Sprout's ADRs also list the alternatives considered and why they were rejected. ADRs are immutable once accepted; a changed decision gets a new ADR that supersedes the old one, so the history of reasoning stays readable.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-architecture.md) | Multi-module, offline-first architecture | Accepted |
| [0002](0002-ledger-as-events.md) | Ledger as a signed, append-only event stream | Accepted |
| [0003](0003-security-model.md) | Security model: identity, signing, and scoped E2EE | Accepted |

Related: [threat model](../THREAT_MODEL.md).
