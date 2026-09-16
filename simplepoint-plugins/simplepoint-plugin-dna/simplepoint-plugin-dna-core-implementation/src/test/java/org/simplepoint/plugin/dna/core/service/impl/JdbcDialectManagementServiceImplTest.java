package org.simplepoint.plugin.dna.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDialectSourceDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.service.dialect.GenericJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.support.JdbcDialectSourceArtifactManager;
import org.simplepoint.plugin.dna.core.service.support.JdbcDialectSourceArtifactManager.LoadedDialect;

@ExtendWith(MockitoExtension.class)
class JdbcDialectManagementServiceImplTest {

  @Mock
  private JdbcDialectSourceDefinitionRepository sourceRepository;

  @Mock
  private JdbcDriverDefinitionRepository driverRepository;

  @Mock
  private JdbcDialectSourceArtifactManager artifactManager;

  private JdbcDialectManagementServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new JdbcDialectManagementServiceImpl(sourceRepository, driverRepository, artifactManager);
  }

  // ---- helpers ----

  private static LoadedDialect classpathDialect(final JdbcDatabaseDialect dialect) {
    return new LoadedDialect(
        dialect,
        JdbcMetadataModels.SourceType.CLASSPATH,
        null,
        "Classpath",
        null,
        null,
        true,
        "类路径方言",
        null
    );
  }

  private static LoadedDialect sourceDialect(
      final JdbcDatabaseDialect dialect,
      final String sourceId,
      final String sourceName
  ) {
    return new LoadedDialect(
        dialect,
        JdbcMetadataModels.SourceType.UPLOAD,
        sourceId,
        sourceName,
        null,
        "/tmp/test.jar",
        true,
        "加载成功",
        Instant.now()
    );
  }

  private static JdbcDialectSourceDefinition newSource(
      final String id,
      final String name,
      final String sourceType,
      final String sourceUrl
  ) {
    JdbcDialectSourceDefinition s = new JdbcDialectSourceDefinition();
    s.setId(id);
    s.setName(name);
    s.setSourceType(sourceType);
    s.setSourceUrl(sourceUrl);
    s.setEnabled(true);
    return s;
  }

  // ---- listLoadedDialects ----

  @Test
  void listLoadedDialectsShouldReturnSortedDescriptors() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of(
        classpathDialect(new MysqlJdbcDatabaseDialect()),
        classpathDialect(new PostgresqlJdbcDatabaseDialect()),
        classpathDialect(new GenericJdbcDatabaseDialect())
    ));
    when(sourceRepository.findAllActive()).thenReturn(List.of());
    when(driverRepository.findAllActive()).thenReturn(List.of());

    List<JdbcMetadataModels.DialectDescriptor> descriptors = service.listLoadedDialects();

    assertEquals(3, descriptors.size());
    // sorted by order: postgresql(10), mysql(20), generic(1000)
    assertEquals("postgresql", descriptors.get(0).code());
    assertEquals("mysql", descriptors.get(1).code());
    assertEquals("generic", descriptors.get(2).code());
  }

  @Test
  void listLoadedDialectsShouldDeduplicateByCodeAndClass() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of(
        classpathDialect(new PostgresqlJdbcDatabaseDialect())
    ));
    JdbcDialectSourceDefinition externalSource = newSource("src-1", "external", "UPLOAD", null);
    when(sourceRepository.findAllActive()).thenReturn(List.of(externalSource));
    // external jar also exposes postgresql dialect - should be deduped
    when(artifactManager.discoverFromJar(externalSource)).thenReturn(List.of(
        sourceDialect(new PostgresqlJdbcDatabaseDialect(), "src-1", "external")
    ));
    when(driverRepository.findAllActive()).thenReturn(List.of());

    List<JdbcMetadataModels.DialectDescriptor> descriptors = service.listLoadedDialects();

    assertEquals(1, descriptors.size());
    assertEquals("postgresql", descriptors.get(0).code());
  }

  @Test
  void listLoadedDialectsShouldBindDriversToDialects() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of(
        classpathDialect(new PostgresqlJdbcDatabaseDialect())
    ));
    when(sourceRepository.findAllActive()).thenReturn(List.of());

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setCode("pg-driver");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.postgresql.Driver");
    when(driverRepository.findAllActive()).thenReturn(List.of(driver));

    List<JdbcMetadataModels.DialectDescriptor> descriptors = service.listLoadedDialects();

    assertEquals(1, descriptors.size());
    assertTrue(descriptors.get(0).boundDriverCodes().contains("pg-driver"));
  }

  @Test
  void listLoadedDialectsShouldContinueWhenJarDiscoveryFails() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of(
        classpathDialect(new GenericJdbcDatabaseDialect())
    ));
    JdbcDialectSourceDefinition badSource = newSource("src-bad", "broken", "UPLOAD", null);
    when(sourceRepository.findAllActive()).thenReturn(List.of(badSource));
    when(artifactManager.discoverFromJar(badSource)).thenThrow(new RuntimeException("JAR not found"));
    when(driverRepository.findAllActive()).thenReturn(List.of());

    // Should not throw
    List<JdbcMetadataModels.DialectDescriptor> descriptors = service.listLoadedDialects();
    assertEquals(1, descriptors.size());
  }

  // ---- listSources ----

  @Test
  void listSourcesShouldMapSourcesWithLoadedDialects() {
    JdbcDialectSourceDefinition source = newSource("src-1", "MySource", "UPLOAD", null);
    source.setLocalJarPath("/data/my.jar");
    source.setLoadedAt(Instant.now());
    source.setLastLoadMessage("加载成功");
    when(sourceRepository.findAllActive()).thenReturn(List.of(source));
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of());
    when(artifactManager.discoverFromJar(source)).thenReturn(List.of(
        sourceDialect(new PostgresqlJdbcDatabaseDialect(), "src-1", "MySource")
    ));

    List<JdbcMetadataModels.DialectSourceSummary> summaries = service.listSources();

    assertEquals(1, summaries.size());
    JdbcMetadataModels.DialectSourceSummary summary = summaries.get(0);
    assertEquals("src-1", summary.id());
    assertEquals("MySource", summary.name());
    assertTrue(summary.discoveredDialectCodes().contains("postgresql"));
  }

  @Test
  void listSourcesShouldReturnEmptyWhenNoSources() {
    when(sourceRepository.findAllActive()).thenReturn(List.of());
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of());

    assertTrue(service.listSources().isEmpty());
  }

  // ---- createUrlSource ----

  @Test
  void createUrlSourceShouldNormalizeAndPersist() {
    JdbcDialectSourceDefinition request = new JdbcDialectSourceDefinition();
    request.setName("My Dialect");
    request.setSourceUrl("https://repo.example.com/dialect.jar");
    request.setDescription("test description");

    JdbcDialectSourceDefinition downloaded = newSource("id-1", "My Dialect", "URL",
        "https://repo.example.com/dialect.jar");
    downloaded.setLocalJarPath("/data/dialect.jar");
    when(artifactManager.downloadDraft(any())).thenReturn(downloaded);
    when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    JdbcDialectSourceDefinition saved = service.createUrlSource(request);

    assertEquals("My Dialect", saved.getName());
    verify(artifactManager).downloadDraft(any());
    verify(sourceRepository).save(downloaded);
  }

  @Test
  void createUrlSourceShouldThrowWhenNameIsBlank() {
    JdbcDialectSourceDefinition request = new JdbcDialectSourceDefinition();
    request.setSourceUrl("https://repo.example.com/dialect.jar");

    assertThrows(IllegalArgumentException.class, () -> service.createUrlSource(request));
  }

  @Test
  void createUrlSourceShouldThrowWhenSourceUrlIsNull() {
    JdbcDialectSourceDefinition request = new JdbcDialectSourceDefinition();
    request.setName("My Dialect");

    assertThrows(IllegalArgumentException.class, () -> service.createUrlSource(request));
  }

  @Test
  void createUrlSourceShouldThrowWhenRequestIsNull() {
    assertThrows(IllegalArgumentException.class, () -> service.createUrlSource(null));
  }

  // ---- removeSources ----

  @Test
  void removeSourcesShouldSoftDeleteFoundSources() {
    JdbcDialectSourceDefinition source = newSource("src-1", "src", "UPLOAD", null);
    when(sourceRepository.findActiveById("src-1")).thenReturn(Optional.of(source));

    service.removeSources(List.of("src-1"));

    ArgumentCaptor<JdbcDialectSourceDefinition> captor =
        ArgumentCaptor.forClass(JdbcDialectSourceDefinition.class);
    verify(sourceRepository).updateById(captor.capture());
    assertTrue(captor.getValue().getDeletedAt() != null);
  }

  @Test
  void removeSourcesShouldSkipNotFoundIds() {
    when(sourceRepository.findActiveById("missing")).thenReturn(Optional.empty());

    service.removeSources(List.of("missing"));

    verify(sourceRepository, never()).updateById(any());
  }

  @Test
  void removeSourcesShouldDoNothingWhenEmptyList() {
    service.removeSources(List.of());
    verify(sourceRepository, never()).findActiveById(any());
  }

  @Test
  void removeSourcesShouldDoNothingWhenNull() {
    service.removeSources(null);
    verify(sourceRepository, never()).findActiveById(any());
  }

  // ---- resolveDialect ----

  @Test
  void resolveDialectShouldReturnBestMatchByOrder() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of(
        classpathDialect(new PostgresqlJdbcDatabaseDialect()),
        classpathDialect(new GenericJdbcDatabaseDialect())
    ));
    when(sourceRepository.findAllActive()).thenReturn(List.of());

    JdbcDatabaseDialect.SupportContext ctx = new JdbcDatabaseDialect.SupportContext(
        "postgresql", "org.postgresql.Driver", "PostgreSQL", null,
        null, null, null, Map.of()
    );

    Optional<JdbcDatabaseDialect> resolved = service.resolveDialect(ctx);

    assertTrue(resolved.isPresent());
    assertEquals("postgresql", resolved.get().code());
  }

  @Test
  void resolveDialectShouldFallbackToGenericWhenNoSpecificMatch() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of(
        classpathDialect(new GenericJdbcDatabaseDialect())
    ));
    when(sourceRepository.findAllActive()).thenReturn(List.of());

    JdbcDatabaseDialect.SupportContext ctx = new JdbcDatabaseDialect.SupportContext(
        "unknown_db", "com.unknown.Driver", "UnknownDB", null,
        null, null, null, Map.of()
    );

    Optional<JdbcDatabaseDialect> resolved = service.resolveDialect(ctx);

    assertTrue(resolved.isPresent());
    assertEquals("generic", resolved.get().code());
  }

  @Test
  void resolveDialectShouldReturnEmptyWhenNoDialectsLoaded() {
    when(artifactManager.discoverClasspathDialects()).thenReturn(List.of());
    when(sourceRepository.findAllActive()).thenReturn(List.of());

    Optional<JdbcDatabaseDialect> resolved = service.resolveDialect(
        new JdbcDatabaseDialect.SupportContext("pg", null, null, null, null, null, null, Map.of())
    );

    assertFalse(resolved.isPresent());
  }
}
