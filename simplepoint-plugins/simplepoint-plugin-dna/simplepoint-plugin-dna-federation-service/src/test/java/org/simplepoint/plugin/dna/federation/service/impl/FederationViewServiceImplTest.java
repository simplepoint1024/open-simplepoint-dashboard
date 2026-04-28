package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.federation.api.constants.FederationCatalogTypes;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.simplepoint.plugin.dna.federation.api.repository.FederationViewRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FederationViewServiceImplTest {

  @Mock
  private FederationViewRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private FederationSchemaRepository schemaRepository;

  @Mock
  private FederationCatalogService catalogService;

  @BeforeEach
  void setUp() {
    lenient().when(detailsProviderService.getDialects(any())).thenReturn(List.of());
  }

  @Test
  void findActiveByIdShouldDecorateWithSchemaInfo() {
    FederationView view = view("v1", "reporting_summary", "reporting_summary", "schema-1");
    FederationSchema schema = schema("schema-1", "reporting", "Reporting", "cat-1");
    when(repository.findActiveById("v1")).thenReturn(Optional.of(view));
    when(schemaRepository.findActiveById("schema-1")).thenReturn(Optional.of(schema));

    Optional<FederationView> result = service().findActiveById("v1");

    assertThat(result).isPresent();
    assertThat(result.get().getSchemaCode()).isEqualTo("reporting");
    assertThat(result.get().getSchemaName()).isEqualTo("Reporting");
  }

  @Test
  void findActiveByIdShouldReturnEmptyWhenViewNotFound() {
    when(repository.findActiveById("v-missing")).thenReturn(Optional.empty());

    Optional<FederationView> result = service().findActiveById("v-missing");

    assertThat(result).isEmpty();
  }

  @Test
  void findActiveByIdShouldSetNullSchemaInfoWhenSchemaNotFound() {
    FederationView view = view("v1", "summary", "summary", "schema-missing");
    when(repository.findActiveById("v1")).thenReturn(Optional.of(view));
    when(schemaRepository.findActiveById("schema-missing")).thenReturn(Optional.empty());

    Optional<FederationView> result = service().findActiveById("v1");

    assertThat(result).isPresent();
    assertThat(result.get().getSchemaCode()).isNull();
    assertThat(result.get().getSchemaName()).isNull();
  }

  @Test
  void findActiveByCodeShouldDecorateWithSchemaInfo() {
    FederationView view = view("v1", "summary_view", "Summary View", "schema-1");
    FederationSchema schema = schema("schema-1", "reporting", "Reporting", "cat-1");
    when(repository.findActiveByCode("summary_view")).thenReturn(Optional.of(view));
    when(schemaRepository.findActiveById("schema-1")).thenReturn(Optional.of(schema));

    Optional<FederationView> result = service().findActiveByCode("summary_view");

    assertThat(result).isPresent();
    assertThat(result.get().getSchemaCode()).isEqualTo("reporting");
  }

  @Test
  void findActiveByCodeShouldReturnEmptyForNullCode() {
    when(repository.findActiveByCode(null)).thenReturn(Optional.empty());

    Optional<FederationView> result = service().findActiveByCode(null);

    assertThat(result).isEmpty();
  }

  @Test
  void limitShouldAddDeletedAtFilterAndDelegateToRepository() {
    FederationView view = view("v1", "summary", "Summary", "schema-1");
    FederationSchema schema = schema("schema-1", "rpt", "Reporting", "cat-1");
    Page<FederationView> page = new PageImpl<>(List.of(view));
    when(repository.limit(any(), any())).thenReturn(page);
    when(schemaRepository.findAllByIds(any())).thenReturn(List.of(schema));

    Page<FederationView> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getSchemaCode()).isEqualTo("rpt");
    verify(repository).limit(any(), any());
  }

  @Test
  void createShouldValidateAndPersistViewUnderVirtualCatalog() {
    FederationSchema schema = schema("schema-1", "rpt", "Reporting", "cat-1");
    FederationCatalog catalog = virtualCatalog("cat-1");
    FederationView entity = newView("summary_view", "Summary", "schema-1", "SELECT 1");
    FederationView saved = view("v-new", "summary_view", "Summary", "schema-1");
    when(repository.findActiveByCode("summary_view")).thenReturn(Optional.empty());
    when(schemaRepository.findActiveById("schema-1")).thenReturn(Optional.of(schema));
    when(catalogService.findActiveById("cat-1")).thenReturn(Optional.of(catalog));
    when(repository.save(any(FederationView.class))).thenReturn(saved);
    when(schemaRepository.findActiveById("schema-1")).thenReturn(Optional.of(schema));

    FederationView result = service().create(entity);

    assertThat(result.getId()).isEqualTo("v-new");
    verify(repository).save(any(FederationView.class));
  }

  @Test
  void createShouldRejectNonVirtualCatalog() {
    FederationSchema schema = schema("schema-1", "rpt", "Reporting", "cat-datasource");
    FederationCatalog catalog = dataSourceCatalog("cat-datasource");
    FederationView entity = newView("summary_view", "Summary", "schema-1", "SELECT 1");
    when(repository.findActiveByCode("summary_view")).thenReturn(Optional.empty());
    when(schemaRepository.findActiveById("schema-1")).thenReturn(Optional.of(schema));
    when(catalogService.findActiveById("cat-datasource")).thenReturn(Optional.of(catalog));

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class, () -> service().create(entity)
    );

    assertThat(ex.getMessage()).contains("虚拟目录");
  }

  @Test
  void createShouldRejectDuplicateCode() {
    FederationView existing = view("v-existing", "summary_view", "Old Summary", "schema-1");
    FederationView entity = newView("summary_view", "Summary", "schema-1", "SELECT 1");
    when(repository.findActiveByCode("summary_view")).thenReturn(Optional.of(existing));

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class, () -> service().create(entity)
    );

    assertThat(ex.getMessage()).contains("summary_view");
  }

  @Test
  void createShouldRejectMissingSchema() {
    FederationView entity = newView("summary_view", "Summary", "schema-missing", "SELECT 1");
    when(repository.findActiveByCode("summary_view")).thenReturn(Optional.empty());
    when(schemaRepository.findActiveById("schema-missing")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service().create(entity));
  }

  @Test
  void createShouldRejectNullSql() {
    FederationView entity = newView("summary_view", "Summary", "schema-1", null);

    assertThrows(IllegalArgumentException.class, () -> service().create(entity));
  }

  @Test
  void modifyByIdShouldValidateAndUpdateView() {
    FederationSchema schema = schema("schema-1", "rpt", "Reporting", "cat-1");
    FederationCatalog catalog = virtualCatalog("cat-1");
    FederationView current = view("v1", "summary_view", "Old Summary", "schema-1");
    FederationView patch = new FederationView();
    patch.setId("v1");
    patch.setSchemaId("schema-1");
    patch.setCode("summary_view");
    patch.setName("New Summary");
    patch.setDefinitionSql("SELECT 2");
    FederationView updated = view("v1", "summary_view", "New Summary", "schema-1");
    when(repository.findActiveById("v1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("summary_view")).thenReturn(Optional.of(current));
    when(schemaRepository.findActiveById("schema-1")).thenReturn(Optional.of(schema));
    when(catalogService.findActiveById("cat-1")).thenReturn(Optional.of(catalog));
    when(repository.findById("v1")).thenReturn(Optional.empty());
    when(repository.updateById(any(FederationView.class))).thenReturn(updated);

    FederationView result = service().modifyById(patch);

    assertThat(result.getId()).isEqualTo("v1");
    verify(repository).updateById(any(FederationView.class));
  }

  @Test
  void modifyByIdShouldRejectNonExistentView() {
    FederationView patch = new FederationView();
    patch.setId("v-missing");
    patch.setSchemaId("schema-1");
    patch.setCode("summary_view");
    patch.setName("Summary");
    patch.setDefinitionSql("SELECT 1");
    when(repository.findActiveById("v-missing")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service().modifyById(patch));
  }

  private FederationViewServiceImpl service() {
    return new FederationViewServiceImpl(
        repository, detailsProviderService, schemaRepository, catalogService
    );
  }

  private static FederationView view(
      final String id,
      final String code,
      final String name,
      final String schemaId
  ) {
    FederationView view = new FederationView();
    view.setId(id);
    view.setCode(code);
    view.setName(name);
    view.setSchemaId(schemaId);
    view.setDefinitionSql("SELECT 1");
    view.setEnabled(true);
    return view;
  }

  private static FederationView newView(
      final String code,
      final String name,
      final String schemaId,
      final String sql
  ) {
    FederationView view = new FederationView();
    view.setCode(code);
    view.setName(name);
    view.setSchemaId(schemaId);
    view.setDefinitionSql(sql);
    return view;
  }

  private static FederationSchema schema(
      final String id,
      final String code,
      final String name,
      final String catalogId
  ) {
    FederationSchema schema = new FederationSchema();
    schema.setId(id);
    schema.setCode(code);
    schema.setName(name);
    schema.setCatalogId(catalogId);
    return schema;
  }

  private static FederationCatalog virtualCatalog(final String id) {
    FederationCatalog catalog = new FederationCatalog();
    catalog.setId(id);
    catalog.setCatalogType(FederationCatalogTypes.VIRTUAL);
    return catalog;
  }

  private static FederationCatalog dataSourceCatalog(final String id) {
    FederationCatalog catalog = new FederationCatalog();
    catalog.setId(id);
    catalog.setCatalogType(FederationCatalogTypes.DATA_SOURCE);
    return catalog;
  }
}
