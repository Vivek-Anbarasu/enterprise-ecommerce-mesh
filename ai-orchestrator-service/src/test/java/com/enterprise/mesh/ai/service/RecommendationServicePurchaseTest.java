package com.enterprise.mesh.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.enterprise.mesh.ai.config.MeshAiProperties;
import com.enterprise.mesh.ai.domain.ProductAffinity;
import com.enterprise.mesh.ai.repository.ProductAffinityRepository;
import com.enterprise.mesh.ai.service.RecommendationService.InteractionType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Purchase recording, which is the server-authoritative half of the model.
 *
 * <p>The interesting property is the one about counting: an order of five pairs of
 * boots is one statement about one category, not five. Weighting it five times would
 * let a single large basket dominate the profile for the whole 90-day retention window,
 * because scores decay by only 15% per subsequent interaction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationServicePurchaseTest {

    @Mock
    private ProductAffinityRepository affinityRepository;

    @Mock
    private CatalogueClient catalogueClient;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        // Defaults rather than a stub: a hand-built MeshAiProperties bypasses
        // @Validated, so this is exactly the object the YAML defaults produce.
        service = new RecommendationService(affinityRepository, catalogueClient,
                new MeshAiProperties());
        when(affinityRepository.findByCustomerIdAndTenantIdAndCategory(anyString(), anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
    }

    private void categorise(String sku, String category) {
        when(catalogueClient.categoryOf(sku, "acme")).thenReturn(category);
    }

    @Test
    @DisplayName("a purchase records one purchase interaction per distinct category")
    void recordsOncePerCategory() {
        categorise("BOOTS-1", "boots");
        categorise("BOOTS-2", "boots");
        categorise("COAT-1", "coats");

        int recorded = service.recordPurchase("cust-1", "acme",
                List.of("BOOTS-1", "BOOTS-2", "COAT-1"));

        assertThat(recorded).isEqualTo(2);
        // Two writes, not three: the duplicate boots SKU is one statement about one
        // category, so recording it twice would double-weight a single basket.
        verify(affinityRepository, times(2)).save(org.mockito.ArgumentMatchers.any(ProductAffinity.class));
    }

    @Test
    @DisplayName("purchases are weighted at PURCHASE, not VIEW")
    void recordsPurchaseWeight() {
        categorise("BOOTS-1", "boots");

        service.recordPurchase("cust-1", "acme", List.of("BOOTS-1"));

        ArgumentCaptor<ProductAffinity> saved = ArgumentCaptor.forClass(ProductAffinity.class);
        verify(affinityRepository).save(saved.capture());
        assertThat(saved.getValue().getScore()).isEqualTo(InteractionType.PURCHASE.weight());
        assertThat(saved.getValue().getCategory()).isEqualTo("boots");
    }

    @Test
    @DisplayName("a SKU the catalogue cannot categorise is skipped, never guessed")
    void unresolvableSkuIsSkipped() {
        categorise("BOOTS-1", "boots");
        when(catalogueClient.categoryOf("MYSTERY-1", "acme")).thenReturn(null);

        int recorded = service.recordPurchase("cust-1", "acme", List.of("BOOTS-1", "MYSTERY-1"));

        // Fabricating a category would be indistinguishable from a real preference
        // afterwards, and the recommendation would be quietly wrong rather than absent.
        assertThat(recorded).isEqualTo(1);
        verify(affinityRepository, times(1)).save(org.mockito.ArgumentMatchers.any(ProductAffinity.class));
    }

    @Test
    @DisplayName("a purchase of entirely unresolvable SKUs records nothing")
    void allUnresolvableRecordsNothing() {
        when(catalogueClient.categoryOf(anyString(), anyString())).thenReturn(null);

        assertThat(service.recordPurchase("cust-1", "acme", List.of("A", "B"))).isZero();
        verify(affinityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the single-affinity lookup is used, not a scan of every category")
    void usesIndexedLookup() {
        categorise("BOOTS-1", "boots");

        service.recordPurchase("cust-1", "acme", List.of("BOOTS-1"));

        // The compound index is {customerId, category}; a query that omits category
        // cannot use it, and on the write path that is the difference between one
        // indexed lookup and a scan of the shopper's whole affinity collection.
        verify(affinityRepository).findByCustomerIdAndTenantIdAndCategory("cust-1", "acme", "boots");
        verify(affinityRepository, never())
                .findByCustomerIdAndTenantId(anyString(), anyString());
    }

    @Test
    @DisplayName("an empty order is a no-op")
    void emptyOrderIsANoOp() {
        assertThat(service.recordPurchase("cust-1", "acme", List.of())).isZero();
        assertThat(service.recordPurchase("cust-1", "acme", null)).isZero();
        assertThat(service.recordPurchase(null, "acme", List.of("A"))).isZero();
        verifyNoSave();
    }

    private void verifyNoSave() {
        verify(affinityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}