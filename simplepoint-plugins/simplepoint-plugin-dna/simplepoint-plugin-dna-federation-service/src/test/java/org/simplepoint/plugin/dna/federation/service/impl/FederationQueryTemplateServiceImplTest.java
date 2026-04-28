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
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryTemplate;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryTemplateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FederationQueryTemplateServiceImplTest {

  @Mock
  private FederationQueryTemplateRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  private FederationQueryTemplateServiceImpl service() {
    return new FederationQueryTemplateServiceImpl(repository, detailsProviderService, dataSourceService);
  }

  // ---- findActiveById ----

  @Test
  void findActiveByIdReturnsDecoratedTemplate() {
    FederationQueryTemplate template = validTemplate();
    template.setId("t1");
    when(repository.findActiveById("t1")).thenReturn(Optional.of(template));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Optional<FederationQueryTemplate> result = service().findActiveById("t1");

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
  void findActiveByIdHandlesNullCatalogId() {
    FederationQueryTemplate template = validTemplate();
    template.setId("t1");
    template.setCatalogId(null);
    when(repository.findActiveById("t1")).thenReturn(Optional.of(template));

    Optional<FederationQueryTemplate> result = service().findActiveById("t1");

    assertThat(result).isPresent();
    // No decoration when catalogId is null
  }

  @Test
  void findActiveByIdSetsNullCatalogWhenDsAbsent() {
    FederationQueryTemplate template = validTemplate();
    template.setId("t1");
    when(repository.findActiveById("t1")).thenReturn(Optional.of(template));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());

    Optional<FederationQueryTemplate> result = service().findActiveById("t1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isNull();
    assertThat(result.get().getCatalogCode()).isNull();
  }

  // ---- findActiveByCode ----

  @Test
  void findActiveByCodeTrimsAndDelegates() {
    FederationQueryTemplate template = validTemplate();
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.of(template));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Optional<FederationQueryTemplate> result = service().findActiveByCode("  TMPL_001  ");

    assertThat(result).isPresent();
  }

  // ---- findAllActivePublic ----

  @Test
  void findAllActivePublicDecoratesResults() {
    FederationQueryTemplate t1 = validTemplate();
    FederationQueryTemplate t2 = validTemplate();
    t2.setCatalogId("catalog-2");
    when(repository.findAllActivePublic()).thenReturn(List.of(t1, t2));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(dataSourceService.findActiveById("catalog-2")).thenReturn(Optional.of(enabledDs("catalog-2", "PG", "pg")));

    List<FederationQueryTemplate> result = service().findAllActivePublic();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getCatalogName()).isEqualTo("MySQL");
    assertThat(result.get(1).getCatalogName()).isEqualTo("PG");
  }

  @Test
  void findAllActivePublicReturnsEmptyListWhenNone() {
    when(repository.findAllActivePublic()).thenReturn(List.of());

    List<FederationQueryTemplate> result = service().findAllActivePublic();

    assertThat(result).isEmpty();
  }

  // ---- countActivePublic ----

  @Test
  void countActivePublicReturnsRepositorySize() {
    when(repository.findAllActivePublic()).thenReturn(List.of(validTemplate(), validTemplate()));

    long count = service().countActivePublic();

    assertThat(count).isEqualTo(2);
  }

  // ---- limit ----

  @Test
  void limitDecoratesPageContent() {
    FederationQueryTemplate template = validTemplate();
    Page<FederationQueryTemplate> page = new PageImpl<>(List.of(template), PageRequest.of(0, 10), 1);
    when(repository.limit(any(), any())).thenReturn((Page) page);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Page<FederationQueryTemplate> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getCatalogName()).isEqualTo("MySQL");
  }

  @Test
  void limitSkipsDecorationForNullCatalogId() {
    FederationQueryTemplate template = validTemplate();
    template.setCatalogId(null);
    Page<FederationQueryTemplate> page = new PageImpl<>(List.of(template), PageRequest.of(0, 10), 1);
    when(repository.limit(any(), any())).thenReturn((Page) page);

    Page<FederationQueryTemplate> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void limitHandlesNullAttributes() {
    Page<FederationQueryTemplate> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    when(repository.limit(any(), any())).thenReturn((Page) empty);

    Page<FederationQueryTemplate> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result.getContent()).isEmpty();
  }

  // ---- create ----

  @Test
  void createSavesValidTemplate() {
    FederationQueryTemplate template = validTemplate();
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.save(template)).thenReturn(template);

    FederationQueryTemplate result = service().create(template);

    assertThat(result).isNotNull();
    verify(repository).save(template);
  }

  @Test
  void createAppliesDefaults() {
    FederationQueryTemplate template = validTemplate();
    template.setIsPublic(null);
    template.setEnabled(null);
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.save(template)).thenReturn(template);

    service().create(template);

    assertThat(template.getIsPublic()).isFalse();
    assertThat(template.getEnabled()).isTrue();
  }

  @Test
  void createWithNullCatalogIdDoesNotRequireDataSource() {
    FederationQueryTemplate template = validTemplate();
    template.setCatalogId(null);
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.empty());
    when(repository.save(template)).thenReturn(template);

    FederationQueryTemplate result = service().create(template);

    assertThat(result).isNotNull();
  }

  @Test
  void createRejectsNullName() {
    FederationQueryTemplate template = validTemplate();
    template.setName(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(template));
  }

  @Test
  void createRejectsNullCode() {
    FederationQueryTemplate template = validTemplate();
    template.setCode(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(template));
  }

  @Test
  void createRejectsNullSql() {
    FederationQueryTemplate template = validTemplate();
    template.setSql(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(template));
  }

  @Test
  void createRejectsDuplicateCode() {
    FederationQueryTemplate template = validTemplate();
    FederationQueryTemplate existing = validTemplate();
    existing.setId("other-id");
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.of(existing));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(template));
    assertThat(ex.getMessage()).contains("模板编码已存在");
  }

  @Test
  void createRejectsAbsentCatalogId() {
    FederationQueryTemplate template = validTemplate();
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(template));
    assertThat(ex.getMessage()).contains("数据源不存在");
  }

  // ---- modifyById ----

  @Test
  void modifyByIdThrowsWhenTemplateNotFound() {
    FederationQueryTemplate entity = validTemplate();
    entity.setId("t-missing");
    when(repository.findActiveById("t-missing")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().modifyById(entity));
    assertThat(ex.getMessage()).contains("查询模板不存在");
  }

  @Test
  void modifyByIdUpdatesTemplate() {
    FederationQueryTemplate current = validTemplate();
    current.setId("t1");
    FederationQueryTemplate entity = validTemplate();
    entity.setId("t1");

    when(repository.findActiveById("t1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.findById("t1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    FederationQueryTemplate result = service().modifyById(entity);

    assertThat(result).isNotNull();
  }

  @Test
  void modifyByIdInheritsNullFieldsFromCurrent() {
    FederationQueryTemplate current = validTemplate();
    current.setId("t1");
    current.setIsPublic(true);
    current.setEnabled(false);

    FederationQueryTemplate entity = validTemplate();
    entity.setId("t1");
    entity.setIsPublic(null);
    entity.setEnabled(null);

    when(repository.findActiveById("t1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("TMPL_001")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.findById("t1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    service().modifyById(entity);

    assertThat(entity.getIsPublic()).isTrue();
    assertThat(entity.getEnabled()).isFalse();
  }

  // ---- helpers ----

  private FederationQueryTemplate validTemplate() {
    FederationQueryTemplate template = new FederationQueryTemplate();
    template.setName("Orders Report");
    template.setCode("TMPL_001");
    template.setCatalogId("catalog-1");
    template.setSql("SELECT * FROM orders");
    return template;
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
