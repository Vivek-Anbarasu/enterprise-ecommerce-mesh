# Architecture Decision Records

An ADR records a decision that was **expensive to reverse**, the alternatives that were
rejected, and the conditions under which the decision should be revisited. It is not a
design document: the code and the README explain *what* the system does. These explain
*why it does it that way* and *what it costs*.

Each record follows the same shape: Context → Decision → Consequences → Alternatives →
Revisit triggers → References. The "Revisit triggers" section is the important one; a
decision with no stated expiry is a decision nobody is allowed to question.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-checkout-saga-orchestration.md) | Checkout is a compensatable orchestrator saga, not choreography and not one transaction | Accepted |
| [0002](0002-transactional-outbox-with-debezium-cdc.md) | Events leave through a transactional outbox read by Debezium, not a polling relay | Accepted |
| [0003](0003-shared-schema-multi-tenancy.md) | One schema with a tenant column on every aggregate, tenant resolved from the JWT claim | Accepted |
| [0004](0004-brute-force-semantic-search.md) | Semantic search scans vectors in MongoDB rather than running a vector database | Accepted |
| [0005](0005-bff-server-side-token-storage.md) | The BFF holds the token bundle; the browser holds only a session cookie | Accepted |

## Conventions

- **Numbering** — four digits, monotonic, never reused. A superseded ADR keeps its number
  and gains `Superseded by ADR-XXXX` in its status line.
- **Status** — `Proposed`, `Accepted`, `Superseded`, or `Deprecated`.
- **One decision per record.** If a record needs the word "and" in its title, it is two
  records.
- **Consequences are mandatory**, including the negative ones. An ADR that lists only
  benefits is marketing, not a decision record.
- **Revisit triggers are measurable** where possible — a product count, a latency budget,
  a participant count — so that "should we change this?" has an answer that is not a
  matter of taste.

## Adding a record

Copy the template below into `docs/adr/NNNN-short-title.md`, add it to the table above,
and link it from the *Design decisions* section of the root README.

```markdown
# ADR-NNNN: <decision in one line, stated as a decision>

- **Status:** Proposed
- **Date:** YYYY-MM-DD

## Context

What forces are at play, and what makes this a decision rather than an obvious choice.

## Decision

What we will do, in the present tense.

## Consequences

### Positive
### Negative
### Operational

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |

## Revisit triggers

The measurable conditions under which this record should be reopened.

## References

- Code, tests, migrations, and diagrams that implement or guard the decision.
```
