# Summary of Recent Architectural Discussions & Decisions

**Service:** `ai-orchestrator-service`  
**Focus:** Recommendation Engine, Semantic Product Search, Model Selection, and Runtime Infrastructure

---

## 1. Context & Structural Fix (Option B Selection)
*   **Observation:** The `RecommendationService.rank()` mathematical method was found to be unreachable dead code. The `RecommendationController.recommendations()` endpoint was short-circuiting responses to an empty list (`List.of()`), completely bypassing the custom personalization heuristics. Furthermore, the two load-bearing arguments (`candidateSkus` and `popularityByCategory`) had zero integration or extraction logic built in the codebase.
*   **Decision:** We rejected dynamic provider routing configurations (speculative over-engineering) and adopted **Option B (Minimal Integration Integration)**:
    1. Extended `CatalogueClient` to fetch real product candidate lists from the `product-catalog-service` paginated `/api/products` HTTP endpoint.
    2. Updated `RecommendationController` to orchestrate preferences, collect candidate items, and execute the heuristic math.
    3. Documented that global popularity computation maps pass an empty `Map.of()`, explicitly zeroing out the trending metric until an upstream analytical data highway is established.

---

## 2. Rejection of Heavily Generative Frameworks on Critical Path
*   **Proposal Eval:** Evaluated running a massive generative model (**Gemma 27B**) via **vLLM** / **Ollama** clusters to handle real-time product ranking and personalization.
*   **Decision:** **Strongly Rejected.** Generative language models are the wrong primitive for real-time homepage array sorting. 
    *   **Latency:** `GET /api/ai/recommendations` is the storefront home page. Introducing an LLM changes a microsecond computation into an unpredictable multi-second execution path, destroying page load performance.
    *   **Determinism & Explainability:** The system requires reproducible sorting arrays so store managers can inspect why items appear. Generative loops introduce random shuffling and break unit assertions (`RecommendationServiceRankTest`).
    *   **Safety Rule:** Generative systems can hallucinate dead SKUs. LLMs may only propose structured query properties (filters, price brackets, categories) to catalog services; they must never synthesize product entities directly.

---

## 3. Core Architectural Strategy: Semantic Search & Model Sizing
*   **Observation:** The microservice POM specifies *"Semantic product search, recommendations and conversational shopping assistant,"* but only the heuristic personalization block was structurally live.
*   **Decisions:**
    1. **Personalization Ranking:** The local **Mathematical Heuristic** remains the definitive, permanent path for homepage item sorting due to microsecond execution, zero-GPU resource footprint, and absolute determinism.
    2. **Semantic Search Primitive:** Selected **`nomic-embed-text`** as the default **Embedding Model** (~137M params) instead of a generative layout. 
        *   Semantic search requires vector mathematical pooling rather than string generation.
        *   **Production Serving Layer:** Deploying `nomic-embed-text` **in-process** via FastEmbed/ONNX Runtime handles calculations in 5–20 milliseconds directly on **standard CPU infrastructure**. This completely avoids network hops, extra open container ports, and heavy GPU cluster software (vLLM) for similarity reads.
    3. **Generative Separation (Phase 3 & 4 Roadmap):** Smaller generative components like **Gemma 2B / 9B Instruct** are appropriate *only* for natural language chat fields (Conversational Assistant) or query-rewriting tasks. They must sit completely off the critical homepage rendering path on a greenfield route (`POST /api/ai/chat`).
    4. **Vector Storage Plan:** Recommended a **brute-force cosine scan inside the existing standalone MongoDB** collection as an immediate infrastructure-free solution. Because a demo catalog contains under 10,000 product nodes, a vector array matrix scan completes in < 10 ms without requiring Atlas or external database migrations (Qdrant/Milvus).
