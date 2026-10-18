package com.enterprise.mesh.catalog.messaging;

/**
 * A catalogue product change, as seen by the semantic indexer.
 *
 * <p>Carries the text that gets embedded rather than just the SKU, which is a deliberate choice
 * against the usual "publish identity, have the consumer fetch" rule. Two reasons:
 *
 * <ul>
 *   <li>The consumer would otherwise need a catalogue endpoint that returns a whole product.
 *       {@code CatalogueClient} resolves one SKU to one category and nothing more, so this would
 *       mean adding an endpoint to a service that does not otherwise need one.</li>
 *   <li>Events are keyed by SKU, so ordering per product is guaranteed. Two rapid edits are
 *       therefore consumed in the order they happened, and the fingerprint in
 *       {@code SemanticIndexService} makes a redundant event a no-op. The staleness a
 *       fetch-on-consume design avoids does not arise.</li>
 * </ul>
 *
 * <p>What is <em>not</em> here matters as much: no price, no stock level, no image URLs. This
 * topic carries catalogue <em>text</em> for embedding. Anything a consumer might react to
 * commercially belongs on its own topic with its own retention.
 *
 * @param active false for a deactivation; the consumer removes the vector rather than indexing it
 */
public record ProductChangedEvent(
        String sku,
        String tenantId,
        String name,
        String brand,
        String description,
        boolean active) {
}