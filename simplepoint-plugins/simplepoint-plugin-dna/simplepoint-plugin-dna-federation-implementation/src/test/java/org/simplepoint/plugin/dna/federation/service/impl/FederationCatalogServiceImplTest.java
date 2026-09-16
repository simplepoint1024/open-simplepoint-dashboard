package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.constants.FederationCatalogTypes;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.repository.FederationCatalogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FederationCatalogServiceImplTest {

  @Mock
  private FederationCatalogRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Test
  void findActiveByCodeShouldResolveGeneratedDataSourceCatalog() {
    JdbcDataSourceDefinition definition = enabledDataSource();
    when(repository.findActiveByCode("PG")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveByCode("PG")).thenReturn(Optional.of(definition));
    FederationCatalogServiceImpl service = service();

    FederationCatalog catalog = service.findActiveByCode("PG").orElseThrow();

    assertThat(catalog.getId()).isEqualTo("data-source:ds-1");
    assertThat(catalog.getCode()).isEqualTo("PG");
    assertThat(catalog.getName()).isEqualTo("PG");
    assertThat(catalog.getCatalogType()).isEqualTo(FederationCatalogTypes.DATA_SOURCE);
  }

  @Test
  void limitShouldIncludeGeneratedDataSourceCatalogs() {
    FederationCatalog virtualCatalog = new FederationCatalog();
    virtualCatalog.setId("catalog-1");
    virtualCatalog.setCode("demo");
    virtualCatalog.setName("Demo");
    virtualCatalog.setEnabled(true);
    when(repository.findAll(anyMap())).thenReturn(List.of(virtualCatalog));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(enabledDataSource()));
    FederationCatalogServiceImpl service = service();

    Page<FederationCatalog> page = service.limit(java.util.Map.of(), PageRequest.of(0, 10));

    assertThat(page.getContent())
        .extracting(FederationCatalog::getCode, FederationCatalog::getCatalogType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("PG", FederationCatalogTypes.DATA_SOURCE),
            org.assertj.core.groups.Tuple.tuple("demo", FederationCatalogTypes.VIRTUAL)
        );
  }

  @Test
  void createShouldRejectManualDataSourceCatalog() {
    FederationCatalog entity = new FederationCatalog();
    entity.setName("PG");
    entity.setCode("PG");
    entity.setCatalogType(FederationCatalogTypes.DATA_SOURCE);
    FederationCatalogServiceImpl service = service();

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.create(entity));

    assertThat(exception.getMessage()).contains("自动生成");
  }

  private FederationCatalogServiceImpl service() {
    return new FederationCatalogServiceImpl(repository, detailsProviderService, dataSourceService);
  }

  private static JdbcDataSourceDefinition enabledDataSource() {
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setCode("PG");
    definition.setEnabled(true);
    definition.setDescription("Primary datasource");
    return definition;
  }
}
