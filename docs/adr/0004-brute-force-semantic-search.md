# ADR-0004: Semantic search scans vectors in MongoDB instead of running a vector database

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** `ai-orchestrator-service`

## Context

Shoppers need to search the catalogue by meaning ("something warm for a hike in the rain"),
not only by keyword. That requires embedding products and queries into the same vector
space and ranking by similarity.

The constraints at the time of the decision:

- **Catalogue size is a few thousand products per tenant**, not millions.
- **The deployment is a standalone `mongod`**, where MongoDB Atlas `$vectorSearch` does not
  exist. The catalogue service already owns that store.
- **The embedding model is a file, not a service.** `nomic-embed-text` runs in-process via
  ONNX Runtime: 768 dimensions, ~137M parameters, ~270 MB of weights, CPU-only, single-digit
  milliseconds per embed.
- **The latency budget is a shopper's attention.** A search that adds a network hop to a
  second service has to justify that hop.

## Decision

**Store unit-length vectors in MongoDB and scan them with cosine similarity.**

- `product_embeddings` holds one row per product per model, with the vector, the model id,
  and the tenant. Vectors are stored unit-length, so cosine similarity is a dot product.
- `SemanticSearchService` scans **one tenant's** vectors and returns the top matches. It is
  read-side only and deliberately separate from `RecommendationService`: conflating them
  would make a model outage look like a change in personalisation.
- **Vectors from a different model are skipped, not scored.** Cosine similarity between two
  unrelated embedding spaces still returns a confident-looking number, which is worse than
  no result. The model id is part of the vector's identity, not a comment.
- **The model loads lazily.** Loading ~270 MB in a constructor blocks the readiness probe
  for a feature that may never be called on that pod. `isAvailable()` reports file
  presence, so callers get an explicit `SemanticSearchUnavailableException` (503) rather
  than a first-request stall.
- **An empty query is a 400, not an empty result.** Returning `[]` for a blank query makes
  a broken search box look like a working one.
- **The index is kept current through `product.events`**, so a catalogue change does not
  require a reindex job.

## Consequences

### Positive

- No new deployment, no new backup story, no new outage mode. The only infrastructure is
  the MongoDB the catalogue already runs.
- No network hop for search: the embed and the scan happen in the same process.
- At the targeted size, a few thousand 768-dimension floats are a few megabytes and the
  arithmetic is single-digit milliseconds.
- Degradation is explicit: an unavailable model is a 503 with a reason, not an empty list.

### Negative

- **Search is O(n) per query** and memory-resident. It does not scale with catalogue size
  the way an approximate-nearest-neighbour index does.
- Every query pays the full scan even when only the top ten results are needed.
- A model change requires re-embedding the catalogue, and until then the old vectors are
  deliberately invisible to search.
- No hybrid ranking (BM25 + vector) without additional work.

### Operational

- The model directory (`mesh.ai.semantic.model-dir`, default `models/nomic-embed-text`) is a
  deployment artefact, not a dependency. A pod without it serves recommendations and
  returns 503 for search.
- The dimension is reported as a constant (768) so callers can size result handling without
  loading the model; a mismatch is caught by the dimension check rather than surfacing as a
  cosine score between incomparable vectors.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **MongoDB Atlas `$vectorSearch`** | Not available on standalone `mongod`, which is what this deployment runs. Revisit if the store moves to Atlas. |
| **pgvector** | Would add a PostgreSQL instance to a service that otherwise uses MongoDB, purely for search — a second store, a second backup, a second failure mode. |
| **Dedicated vector database** (Qdrant, Milvus, Weaviate) | Adds a deployment, a backup story, and an outage mode to save time nobody is waiting on at this catalogue size. Justified at a scale this project does not target. |
| **Hosted embeddings API** | Adds a network hop, a per-token bill, and an external dependency to a workload that fits comfortably on CPU in-process. |

## Revisit triggers

- **About ten thousand products per tenant** — the recorded crossover point. At that size
  the scan stops being single-digit milliseconds and an ANN index earns its deployment.
- Search p95 exceeds roughly 50 ms at the target catalogue size.
- The platform moves to Atlas, at which point `$vectorSearch` removes the scan for free.
- Hybrid keyword + semantic ranking becomes a product requirement.

## References

- `ai-orchestrator-service/src/main/java/com/enterprise/mesh/ai/semantic/SemanticSearchService.java`
- `ai-orchestrator-service/src/main/java/com/enterprise/mesh/ai/semantic/OnnxTextEmbedder.java`
- `ai-orchestrator-service/src/main/java/com/enterprise/mesh/ai/semantic/SemanticProperties.java`
- `ai-orchestrator-service/src/main/java/com/enterprise/mesh/ai/domain/ProductEmbedding.java`
- `ai-orchestrator-service/src/main/java/com/enterprise/mesh/ai/repository/ProductEmbeddingRepository.java`
- `ai-orchestrator-service/src/main/java/com/enterprise/mesh/ai/messaging/ProductEventListener.java`
