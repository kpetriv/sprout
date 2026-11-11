# Sprout security and disclosure policy

<!-- repo-only -->
> **Source of the published page.** Rendered with the privacy policy by `tools/privacy/render.py` to `server/public/security/index.html`, and summarised in `server/public/.well-known/security.txt` and `server/public/security.txt` (RFC 9116, written by `npm --prefix server run build:static`). Placeholders come from `docs/privacy.properties`. GitHub also shows this file as the repository's security policy. The safe-harbour paragraph was accepted at the owner's legal review on 2026-11-11 (D-REL-4).
<!-- /repo-only -->

- **Effective date:** {{EFFECTIVE_DATE}}
- **Report to:** [{{SECURITY_CONTACT}}]({{SECURITY_CONTACT_URI}})
- **Maintained by:** {{DEVELOPER_NAME}}

We want Sprout to be safe for the families who use it, and we welcome reports from anyone who finds a weakness. This page says what is in scope, how to report, and what you can expect from us.

## Scope

In scope:

- The Sprout Android app (`dev.kpetriv.sprout`), release builds from Google Play.
- The Sprout server (the household, device, linking, export and deletion API behind the app) and the pages it serves, including this one.
- Sprout's Cloud Firestore security rules: anything that lets one household read or change another household's data, lets a kid phone act as a parent, or lets a removed phone keep writing.
- Sprout's cryptography as designed: the signed ledger, device and parent keys, end-to-end encrypted chat, voice notes and photos.
- The source repository and its build and release workflows.

Out of scope:

- Google's and Vercel's own services and infrastructure (Firebase, Google Play services, Play Integrity, AICore, Vercel). Please report those to Google or Vercel.
- Attacks that need a phone that is already unlocked and in the attacker's hands with the parent area open, or a rooted or otherwise compromised operating system, unless they break a guarantee we state (for example, signing a money entry without the parent's fingerprint).
- Denial of service by volume, spam, and social engineering of our users or of us.
- Reports from automated scanners without a demonstrated impact, missing hardening headers on static pages without an exploit, and clickjacking of pages with no actions.
- The honour-system time bank: Sprout records screen time, it does not enforce it.

## How to report

E-mail [{{SECURITY_CONTACT}}]({{SECURITY_CONTACT_URI}}) with:

- what you found and where (app version, server endpoint, rule, or commit);
- the steps to reproduce it, and what an attacker gains;
- whether, and how, you would like to be credited.

Please use only test households you created yourself. Do not access, change or delete data that is not yours, do not keep any personal data you come across, and stop and tell us as soon as you see someone else's data.

## What to expect

- We acknowledge your report within 5 working days and tell you whether we can reproduce it.
- We keep you updated at least every 30 days until it is fixed.
- **Coordinated disclosure: 90 days.** We aim to ship a fix within 90 days of your report, and ask you not to publish details before a fix is available or the 90 days have passed, whichever comes first. If a fix needs longer, we will explain why and agree a date with you. If a vulnerability is being actively exploited, we may both publish sooner.
- We credit reporters in the release notes unless you ask us not to.
- **There is no bug bounty.** Sprout is a one-person project and cannot pay for reports.

## Safe harbour

<!-- repo-only -->
> **Legal review (owner, 2026-11-11, D-REL-4):** the paragraph below is published as written.
<!-- /repo-only -->

If you make a good-faith effort to follow this policy, we will not pursue or support legal action against you for your research, and we will consider it authorised. Good faith means: testing only against accounts and households you own, not degrading the service for others, not accessing or keeping other people's data beyond what is needed to show the problem, and giving us reasonable time to fix it before disclosure. If in doubt, ask us first at [{{SECURITY_CONTACT}}]({{SECURITY_CONTACT_URI}}).

## Related

- [Privacy policy](PRIVACY.md)
- [`security.txt`]({{SECURITY_TXT_URL}})
