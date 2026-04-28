package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.DataAsset;
import org.simplepoint.plugin.dna.federation.api.repository.DataAssetRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class DataAssetServiceImplTest {

  @Mock
  private DataAssetRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  private DataAssetServiceImpl service() {
    return new DataAssetServiceImpl(repository, detailsProviderService, dataSourceService);
  }

  // ---- findActiveById ----

  @Test
  void findActiveByIdReturnsDecoratedAsset() {
    DataAsset asset = validAsset();
    asset.setId("a1");
    when(repository.findActiveById("a1")).thenReturn(Optional.of(asset));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Optional<DataAsset> result = service().findActiveById("a1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isEqualTo("MySQL");
    assertThat(result.get().getCatalogCode()).isEqualTo("mysql");
  }

  @Test
  void findActiveByIdReturnsEmptyWhenNotFound() {
    when(repository.findActiveById("missing")).thenReturn(Optional.empty());

    assertThat(service().findActiveById("missing")).isEmpty();
  }

  @Test
  void findActiveByIdSetsNullWhenDsAbsent() {
    DataAsset asset = validAsset();
    asset.setId("a1");
    when(repository.findActiveById("a1")).thenReturn(Optional.of(asset));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());

    Optional<DataAsset> result = service().findActiveById("a1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isNull();
    assertThat(result.get().getCatalogCode()).isNull();
  }

  // ---- findActiveByCode ----

  @Test
  void findActiveByCodeTrimsAndDelegates() {
    DataAsset asset = validAsset();
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.of(asset));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Optional<DataAsset> result = service().findActiveByCode("  ASSET_001  ");

    assertThat(result).isPresent();
  }

  @Test
  void findActiveByCodeReturnsEmptyForNull() {
    when(repository.findActiveByCode(null)).thenReturn(Optional.empty());

    assertThat(service().findActiveByCode(null)).isEmpty();
  }

  // ---- limit ----

  @Test
  void limitDecoratesPageContent() {
    DataAsset asset = validAsset();
    Page<DataAsset> page = new PageImpl<>(List.of(asset), PageRequest.of(0, 10), 1);
    when(repository.limit(any(), any())).thenReturn((Page) page);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "PG", "pg")));

    Page<DataAsset> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getCatalogName()).isEqualTo("PG");
    assertThat(result.getContent().get(0).getCatalogCode()).isEqualTo("pg");
  }

  @Test
  void limitSetsNullCatalogWhenDsMissing() {
    DataAsset asset = validAsset();
    Page<DataAsset> page = new PageImpl<>(List.of(asset), PageRequest.of(0, 10), 1);
    when(repository.limit(any(), any())).thenReturn((Page) page);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());

    Page<DataAsset> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent().get(0).getCatalogName()).isNull();
    assertThat(result.getContent().get(0).getCatalogCode()).isNull();
  }

  @Test
  void limitHandlesNullAttributes() {
    Page<DataAsset> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    when(repository.limit(any(), any())).thenReturn((Page) empty);

    Page<DataAsset> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result.getContent()).isEmpty();
  }

  // ---- create ----

  @Test
  void createSavesValidAsset() {
    DataAsset asset = validAsset();
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.save(asset)).thenReturn(asset);

    DataAsset result = service().create(asset);

    assertThat(result).isNotNull();
    verify(repository).save(asset);
  }

  @Test
  void createAppliesEnabledDefault() {
    DataAsset asset = validAsset();
    asset.setEnabled(null);
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.save(asset)).thenReturn(asset);

    service().create(asset);

    assertThat(asset.getEnabled()).isTrue();
  }

  @Test
  void createRejectsNullName() {
    DataAsset asset = validAsset();
    asset.setName(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(asset));
  }

  @Test
  void createRejectsNullCode() {
    DataAsset asset = validAsset();
    asset.setCode(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(asset));
  }

  @Test
  void createRejectsNullCatalogId() {
    DataAsset asset = validAsset();
    asset.setCatalogId(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(asset));
  }

  @Test
  void createRejectsNullAssetType() {
    DataAsset asset = validAsset();
    asset.setAssetType(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(asset));
  }

  @Test
  void createRejectsInvalidAssetType() {
    DataAsset asset = validAsset();
    asset.setAssetType("SCHEMA");

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(asset));
    assertThat(ex.getMessage()).contains("资产类型必须为");
  }

  @Test
  void createRejectsDuplicateCode() {
    DataAsset asset = validAsset();
    DataAsset existing = validAsset();
    existing.setId("other-id");
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.of(existing));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(asset));
    assertThat(ex.getMessage()).contains("资产编码已存在");
  }

  @Test
  void createRejectsDisabledDataSource() {
    DataAsset asset = validAsset();
    JdbcDataSourceDefinition ds = enabledDs("catalog-1", "MySQL", "mysql");
    ds.setEnabled(false);
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(ds));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(asset));
    assertThat(ex.getMessage()).contains("数据源不存在或未启用");
  }

  @Test
  void createAcceptsAllValidAssetTypes() {
    for (String type : List.of("TABLE", "VIEW", "ALL")) {
      DataAsset asset = validAsset();
      asset.setCode("CODE_" + type);
      asset.setAssetType(type);
      when(repository.findActiveByCode("CODE_" + type)).thenReturn(Optional.empty());
      when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
      when(repository.save(asset)).thenReturn(asset);

      DataAsset result = service().create(asset);
      assertThat(result).isNotNull();
    }
  }

  // ---- modifyById ----

  @Test
  void modifyByIdThrowsWhenAssetNotFound() {
    DataAsset entity = validAsset();
    entity.setId("a-missing");
    when(repository.findActiveById("a-missing")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().modifyById(entity));
    assertThat(ex.getMessage()).contains("数据资产不存在");
  }

  @Test
  void modifyByIdUpdatesAsset() {
    DataAsset current = validAsset();
    current.setId("a1");
    DataAsset entity = validAsset();
    entity.setId("a1");

    when(repository.findActiveById("a1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.findById("a1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    DataAsset result = service().modifyById(entity);

    assertThat(result).isNotNull();
  }

  @Test
  void modifyByIdInheritsEnabledFromCurrent() {
    DataAsset current = validAsset();
    current.setId("a1");
    current.setEnabled(false);
    DataAsset entity = validAsset();
    entity.setId("a1");
    entity.setEnabled(null);

    when(repository.findActiveById("a1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("ASSET_001")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.findById("a1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    service().modifyById(entity);

    assertThat(entity.getEnabled()).isFalse();
  }

  // ---- helpers ----

  private DataAsset validAsset() {
    DataAsset asset = new DataAsset();
    asset.setName("Customer Orders");
    asset.setCode("ASSET_001");
    asset.setCatalogId("catalog-1");
    asset.setAssetType("TABLE");
    return asset;
  }

  private static JdbcDataSourceDefinition enabledDs(final String id, final String name, final String code) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setName(name);
    ds.setCode(code);
    ds.setEnabled(true);
    return ds;
  }
}
