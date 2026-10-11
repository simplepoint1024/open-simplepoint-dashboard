package org.simplepoint.plugin.dna.federation.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class FederationMetadataCacheServiceTest {

  @Test
  void invalidatesOnlyScopesContainingTheChangedDatasource() {
    FederationMetadataCacheService cache = new FederationMetadataCacheService();
    String firstSourceBefore = cache.scopedKey(List.of("source-a"), "tables");
    String secondSourceBefore = cache.scopedKey(List.of("source-b"), "tables");

    cache.invalidateDataSource("source-a");

    assertThat(cache.scopedKey(List.of("source-a"), "tables"))
        .isNotEqualTo(firstSourceBefore);
    assertThat(cache.scopedKey(List.of("source-b"), "tables"))
        .isEqualTo(secondSourceBefore);
  }

  @Test
  void runtimeBridgeVersionsMetadataBeforeClosingCalciteAssemblies() {
    FederationMetadataCacheService cache = mock(FederationMetadataCacheService.class);
    FederationCalciteCatalogAssembler assembler = mock(FederationCalciteCatalogAssembler.class);
    FederationRuntimeMetadataCacheInvalidator invalidator =
        new FederationRuntimeMetadataCacheInvalidator(cache, assembler);

    invalidator.invalidate(new RuntimeMetadataTarget(
        "source-a",
        "catalog-a",
        "schema-a",
        "table-a"
    ));

    InOrder order = inOrder(cache, assembler);
    order.verify(cache).invalidateDataSource("source-a");
    order.verify(assembler).invalidateDataSource("source-a");
  }

  @Test
  void redisVersionReadFailureForcesNewCacheTokens() {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("dna:jdbc:meta-version:source-a"))
        .thenThrow(new IllegalStateException("redis unavailable"));
    FederationMetadataCacheService cache = new FederationMetadataCacheService(redisTemplate);

    String first = cache.versionToken(List.of("source-a"));
    String second = cache.versionToken(List.of("source-a"));

    assertThat(second).isNotEqualTo(first);
  }
}
