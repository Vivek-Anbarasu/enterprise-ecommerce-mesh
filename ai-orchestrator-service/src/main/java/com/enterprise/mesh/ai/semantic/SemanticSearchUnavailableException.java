package com.enterprise.mesh.ai.semantic;

/**
 * Search cannot run because the embedding model is not loaded.
 *
 * <p>Thrown rather than handled by returning an empty result set, because "semantic search is
 * not deployed" and "nothing matched your query" are different answers. Returning empty would
 * make a missing model look like a working search that found nothing, which is the same
 * misleading-silence problem {@code mesh.ai.provider} had before it started warning.
 */
public class SemanticSearchUnavailableException extends RuntimeException {

    public SemanticSearchUnavailableException(String message) {
        super(message);
    }
}