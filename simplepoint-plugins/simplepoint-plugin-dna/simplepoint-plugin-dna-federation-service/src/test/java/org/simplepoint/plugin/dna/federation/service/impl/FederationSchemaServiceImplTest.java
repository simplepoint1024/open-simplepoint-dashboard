package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.federation.api.constants.FederationCatalogTypes;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;

@ExtendWith(MockitoExtension.class)
class FederationSchemaServiceImplTest {

  @Mock
  private FederationSchemaRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private FederationCatalogService catalogService;

  @Test
  void createShouldRejectDataSourceCatalog() {
    FederationCatalog dataSourceCatalog = new FederationCatalog();
    dataSourceCatalog.setId("data-source:ds-1");
    dataSourceCatalog.setCode("PG");
    dataSourceCatalog.setName("PG");
    dataSourceCatalog.setCatalogType(FederationCatalogTypes.DATA_SOURCE);
    FederationSchema entity = new FederationSchema();
    entity.setCatalogId("data-source:ds-1");
    entity.setName("Reporting");
    entity.setCode("reporting");
    when(repository.findActiveByCode("reporting")).thenReturn(Optional.empty());
    when(catalogService.findActiveById("data-source:ds-1")).thenReturn(Optional.of(dataSourceCatalog));
    FederationSchemaServiceImpl service = new FederationSchemaServiceImpl(repository, detailsProviderService, catalogService);

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.create(entity));

    assertThat(exception.getMessage()).contains("虚拟目录");
  }
}
