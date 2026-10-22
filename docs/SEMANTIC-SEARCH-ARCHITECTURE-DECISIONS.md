# Architectural Decision Record: Semantic Product Search
## Model selection, serving layer, and vector storage for `ai-orchestrator-service`

**Status:** Proposed
**Scope:** `ai-orchestrator-service`, `product-catalog-service`
**Supersedes:** nothing
**Related:** `docs/ARCHITECTURE.md`, `docs/REQUEST-FLOW-END-TO-END.md` §15.12

---

## 1. Context

`ai-orchestrator-service` is described in its POM as *"Semantic product search, recommendations and
conversational shopping assistant"* and in `AiOrchestratorApplication` as *"Personalisation and
conversational search"*. Only personalisation is built. The other two capabilities are named but absent.

The question addressed here is which of them to build next, and specifically **which model and which
serving layer** should back it.

This decision follows a separate piece of work that closed the recommendation path: `rank()` is now
called, candidates are fetched from the real catalogue, and `mesh.ai.*` configuration is bound and
validated rather than declared and ignored. That work deliberately left `mesh.ai.provider` inert —
it logs a warning if set to anything other than `none`, but selects nothing. This document describes
what would actually make that key select something.

---

## 2. The decision

**Build (ii): semantic similarity search over the product catalogue.**

An embedding model encodes products and shopper queries into vectors; a query returns its nearest
catalogue neighbours. No text generation, no conversational loop, no change to `RecommendationService`.

### 2.1 What is explicitly rejected, and why

| Option | Decision | Rationale |
| :--- | :---: | :--- |
| **(i) NL → structured catalogue query** | Deferred | Composable and cheap, but it is an interface over filtering that already exists in `ProductCatalogService.browse`. Worth building once (ii) exists, because a vector result is a better source of candidate keywords. |
| **(iii) Multi-turn shopping assistant** | Deferred | This is the option that genuinely requires a generative model. It is also the largest surface — streaming, conversation state, prompt-injection handling. Building it before the catalogue is queryable in natural language would mean asking a model to answer questions about a catalogue it cannot yet search. |
| **LLM as the ranking engine** | Rejected | Covered in §3.3. The ranking path is a pure function, is latency-critical, and is explainable by design. |

### 2.2 The capability this actually unlocks

A shopper who searches *"something warm for a rainy hike"* currently gets nothing, because `Product`
has no text index and `ProductRepository` offers only four finders — by SKU, by id, by category, by
tenant. There is no full-text search anywhere in the mesh.

Semantic search is the smallest primitive that makes that query work, and it is the capability the POM
already names.

---

## 3. Model selection

### 3.1 The load-bearing correction: embeddings, not generation

**A generative model is the wrong primitive for this.**

`Gemma 3 4B` is a decoder-only transformer. It generates tokens; it does not produce sentence
embeddings. Using it for similarity search means pooling hidden states from a model never trained to
produce them — worse results at a fraction of the cost of a purpose-built encoder.

The model for (ii) is an **embedding model**:

| Model | Params | Dimensions | Notes |
| :--- | :---: | :---: | :--- |
| `all-MiniLM-L6-v2` | ~22M | 384 | Fastest; adequate for a demo catalogue. Good smoke-test target. |
| **`nomic-embed-text`** | ~137M | 768 | **Default choice.** Strong retrieval quality at small size, runs on CPU. |
| `mxbai-embed-large` | ~335M | 1024 | Highest quality of the three; noticeably slower. |

### 3.2 A practical trap: context truncation

Embedding models have short context limits — commonly 512 or 2048 tokens. `Product.description` is free
text of unbounded length and **will** exceed this.

Two consequences to settle before indexing anything:

1. **Confirm the truncation behaviour of the chosen model.** Truncation is silent, and two products that
   differ only past the cutoff embed identically. This looks like a ranking bug, not a truncation bug.
2. **Decide the embedding input explicitly.** Recommended: `name` + `brand` + `description`, concatenated
   with separators, truncated from the *start* of the description rather than the end — the opening
   sentences of a product description carry the most searchable signal.

### 3.3 Why not route ranking through a model

Rejected in the previous work and restated here, because it will be proposed again:

- **Latency.** `GET /api/ai/recommendations` is the storefront home page — the most frequent read in the
  service, which is why `preferredCategories` is `@Cacheable`. A generative call adds hundreds of
  milliseconds to seconds, non-deterministically, on every page load.
- **Quality.** Ranking ≤100 candidates by `1/(1+index)` affinity decay is not a task a 27B model does
  better. It does it slower, non-reproducibly, and with a higher error rate.
- **Determinism.** `rank()` now has an explicit SKU tie-break so two page loads that changed nothing
  produce an identical shelf. Generative models offer no such guarantee.
- **Explainability.** The class javadoc states the requirement directly: *"a merchant needs to be able
  to explain why a product was recommended."*
- **Testability.** `RecommendationServiceRankTest` asserts exact scores (0.8, 0.4). That is only possible
  while ranking is arithmetic.

### 3.4 Structural rule for any future generative use

**A model may propose a query. It may never produce a product result.**

A model asked for *"products for a rainy hike"* will invent SKUs. Those SKUs are not in the catalogue,
the storefront renders a dead link, and the shopper is told a product exists that does not. The
catalogue remains the only authority on what exists. Any generative feature must emit a structured
query — category, keywords, price band — which `product-catalog-service` then executes.

---

## 4. Serving layer: Ollama vs in-process vs vLLM

### 4.1 Comparison

| Dimension | Ollama | In-process (FastEmbed / ONNX) | vLLM |
| :--- | :--- | :--- | :--- |
| Setup | one binary, `ollama pull` | a Maven dependency | GPU node, container, model lifecycle |
| Memory management | quantises, auto GPU offload, CPU fallback | bundle size only | you size KV cache and batch |
| Throughput | modest, one request at a time | per-pod, no cross-pod sharing | continuous batching, PagedAttention, 10–50x |
| Multi-GPU | no | n/a | tensor + pipeline parallelism |
| Embedding support | works | purpose-built | **weaker — vLLM is built for generation** |
| Operational metrics | minimal | Micrometer, in-process | Prometheus, health endpoints, p99 |
| Failure modes | process down, port busy | none beyond the pod | node down, GPU OOM, model load |
| Ownership burden | near zero | none | needs someone who owns GPU ops |

### 4.2 The recommendation

**Development: Ollama.** Zero setup, one command per model, GGUF quantisation handled for you.

**Production: in-process.** This is the non-obvious part. An embedding model on CPU returns in 5–20 ms.
Running `nomic-embed-text` in-process via FastEmbed (ONNX Runtime, no PyTorch) removes a network hop, a
container, a port, and a failure mode from the request path. Ollama is a development convenience; at
this workload it is the *worse* production choice, not the better one.

**vLLM: not now.** Its entire value proposition is throughput per GPU, and this service has no
throughput problem.

### 4.3 When vLLM becomes correct

Concrete thresholds, not intuition:

| Trigger | Recommendation |
| :--- | :--- |
| Embedding workload, **any** scale | In-process ONNX / FastEmbed / HuggingFace TEI. **Not vLLM.** |
| < 10 QPS, < 50 concurrent, single node | Ollama or in-process |
| 50–500 QPS generation, dedicated GPU | vLLM starts to earn its keep; prefix caching can cut token cost substantially when requests share a system prompt |
| 1000+ QPS, multi-GPU, p99 SLOs | vLLM, scaled horizontally |

The trigger is **sustained concurrency on hardware dedicated to it**. Not model size.

---

## 5. When Gemma 3 27B or vLLM would be justified

### 5.1 Gemma 3 27B — hardware gate first

| Precision | Memory | Minimum hardware |
| :--- | :---: | :--- |
| bf16 | ~54 GB | multi-GPU node |
| fp8 | ~27 GB | multi-GPU node |
| 4-bit | ~16 GB | large single GPU |

It will not run on a free tier, and it is not a realistic fit for the current AKS node pool.

Genuinely justified for: long-context document understanding, multi-step constraint solving across
catalogue *and* order history (*"a gift for someone who hikes, no synthetics, under £100, who bought a
jacket here two years ago"*), code generation, nuanced instruction following.

**Not** justified for: ranking, classification, query rewriting, or entity extraction. Those are
small-model or zero-model jobs, and a 27B model is strictly worse at them — slower, and less predictable
because it is not doing anything a 4B model cannot.

Cost note: a 2-GPU AKS node burns money while idle. If this is ever pursued, gate it behind KEDA on
GPU utilisation so it scales to zero.

### 5.2 Gemma 3 4B — the right size, for a later phase

4B is ample for **(i)** query rewriting and **(iii)** multi-turn refinement. It is the model to reach
for when the conversational layer arrives — not now, and not for search.

Note: Gemma is not Apache-2.0. It ships under Google's custom Gemma Terms of Use with a
prohibited-use policy. Usually not a blocker; worth recording for an enterprise codebase.

---

## 6. Vector storage — the actual blocker

**This is the hard part of (ii), and it is not the model choice.**

`ai-orchestrator-service` connects to `mongodb://localhost:27017` — a **standalone** `mongod`. MongoDB
Atlas Vector Search requires Atlas. It is not available against a standalone deployment, so the usual
answer is unavailable without changing the datastore the whole project depends on.

| Option | Complexity | Fits standalone Mongo | Notes |
| :--- | :--- | :---: | :--- |
| **Brute-force cosine over vectors stored in existing Mongo** | Low | Yes | **Recommended for now.** O(n) per query, but a demo catalogue is hundreds to low thousands of products — sub-10 ms for a few thousand 768-dim vectors. Zero new infrastructure. |
| In-memory HNSW in `ai-orchestrator-service` | Medium | Yes | Fast at scale, but **rebuilt on every restart and divergent between replicas** — two pods can return different results for the same query. Given this codebase's care about tenant-scoped keys and eviction correctness, that is a real consistency problem. |
| Qdrant / pgvector / Milvus | High | Yes | The right answer at real scale. A new datastore, new deployment, new backup story, one more thing to page someone about at 3am. |
| MongoDB Atlas Vector Search | High | **No — requires Atlas** | Cleanest, but migrates the datastore for the whole project. |

### 6.1 The replica-consistency trap

Any index held in-process has the same flaw, and it deserves stating explicitly because it is
invisible in a single-pod dev environment: **two replicas will disagree.** A shopper hitting pod A
sees one result set, pod B another, and the behaviour changes when the deployment rolls.

If brute force in Mongo is chosen, the index is derived state — deletable, rebuildable, and never the
authority. That matches how the rest of this mesh treats Redis: an optimisation with eviction
guarantees, never a source of truth.

---

## 7. Implementation plan

Phase-gated, each independently shippable.

**Phase 1 — Embed and store.** Add the embedding model in-process. Index `name` + `brand` + `description`
per product, keyed tenant-first: `{tenantId}:{sku}`, matching the convention used by every Redis key and
Mongo unique index in this codebase. Vectors live alongside products in the existing store.

Hooks, not polling. `product-catalog-service` currently publishes **nothing** — it has no producer, no
listener, and no `common-messaging` dependency at all, so `EventPublisher` is not even on its classpath.
An event-driven approach therefore means a new topic *and* a new dependency; the mesh already does this
pattern from `order-service` and `shipping-delivery-service`, so it would be consistent rather than novel.
**Decision required (§8).**

**Phase 2 — Query endpoint.** `GET /api/ai/search?q=...` in `ai-orchestrator-service`. Embeds the query,
brute-force scores against tenant-scoped vectors, returns the top N. Reuse the existing Redis TTL and
`key-prefix` discipline so query results cannot leak across tenants.

`RecommendationService` is not touched by this phase.

**Phase 3 — (i) structured query generation.** Only once (ii) is live. Small model, structured output
only, catalogue remains authoritative.

**Phase 4 — (iii) conversation.** Only if there is demand. Streaming, conversation state, prompt-injection
handling.

---

## 8. Open questions

1. **How are products embedded — on write, or on demand?** A new Kafka topic for product writes matches
   the mesh's event-driven design and is the more consistent choice; on-write synchronous embedding
   couples the catalogue's write latency to model inference. Undecided. Note that the event-driven option
   is currently *more* work, not less — see Phase 1.
2. **What is the catalogue size?** Brute force is correct below roughly 10k products and wrong above it.
   This number determines whether §6 stays simple or becomes a datastore project.
3. **Does `mesh.ai.provider` get repurposed or split?** It currently reads `"none"` and warns on anything
   else. Embedding models are not "providers" in the generative sense. Splitting into
   `mesh.ai.embedding-model` and leaving `provider` for a future generative feature may be more honest —
   but renaming a key that already appears in two Helm values files is a deployment-visible change.
4. **Tenant isolation of the vector store.** Brute force over a shared collection risks cross-tenant
   leakage if a filter is ever missed. Needs the same treatment as the existing tenant-scoped key rule.

---

## 9. Summary

| Question | Answer |
| :--- | :--- |
| What do we build? | (ii) semantic similarity search |
| Which model? | `nomic-embed-text` — an embedding model, **not** Gemma 3 4B |
| Which serving layer? | Ollama in development, **in-process (FastEmbed/ONNX) in production**, vLLM not at all today |
| Where do vectors live? | Brute-force cosine over vectors in the existing Mongo. Not Atlas Vector Search — standalone `mongod` does not support it |
| When does vLLM become right? | 50+ QPS generation on dedicated GPUs. Never for embeddings |
| When does Gemma 3 27B become right? | Long-context or multi-step reasoning on a multi-GPU node you have decided to pay for |
| When does Gemma 3 4B become right? | Phase 3 or 4 — query rewriting and multi-turn refinement, never search |