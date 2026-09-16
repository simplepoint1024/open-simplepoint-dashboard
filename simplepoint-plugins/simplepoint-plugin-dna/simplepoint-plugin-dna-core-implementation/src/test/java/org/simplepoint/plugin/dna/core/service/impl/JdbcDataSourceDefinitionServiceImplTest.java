package org.simplepoint.plugin.dna.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDataSourceDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.spi.JdbcManagedDataSourceFactory;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDataSourceConnectionResult;
import org.simplepoint.plugin.dna.core.service.support.JdbcDriverArtifactManager;

@ExtendWith(MockitoExtension.class)
class JdbcDataSourceDefinitionServiceImplTest {

  @Mock
  private JdbcDataSourceDefinitionRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDriverDefinitionRepository driverRepository;

  @Mock
  private JdbcDriverArtifactManager artifactManager;

  @Mock
  private Connection connection;

  @Mock
  private DatabaseMetaData metaData;

  @Test
  void createShouldRejectJdbcUrlThatDoesNotMatchDriverPattern() {
    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setCode("mysql");
    driver.setJdbcUrlPattern("^jdbc:mysql://.*$");
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository,
        detailsProviderService,
        driverRepository,
        artifactManager,
        List.of(new StubFactory(new SimpleDataSource(new EmptyDataSource()))),
        List.of()
    );
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setName("demo");
    definition.setCode("demo");
    definition.setDriverId("driver-1");
    definition.setJdbcUrl("jdbc:postgresql://localhost:5432/demo");

    assertThrows(IllegalArgumentException.class, () -> service.create(definition));
  }

  @Test
  void connectShouldBuildAndCacheSimpleDataSource() throws Exception {
    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setCode("mysql");
    driver.setName("MySQL");
    driver.setJdbcUrlPattern("^jdbc:mysql://.*$");
    driver.setEnabled(true);
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setName("demo");
    definition.setCode("demo");
    definition.setDriverId("driver-1");
    definition.setJdbcUrl("jdbc:mysql://localhost:3306/demo");
    definition.setEnabled(true);
    SimpleDataSource simpleDataSource = new SimpleDataSource(new SingleConnectionDataSource(connection));
    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(driverRepository.findAllByIds(any())).thenReturn(List.of(driver));
    when(repository.save(any(JdbcDataSourceDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("MockDB");
    when(metaData.getDatabaseProductVersion()).thenReturn("1.0");
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository,
        detailsProviderService,
        driverRepository,
        artifactManager,
        List.of(new StubFactory(simpleDataSource)),
        List.of()
    );

    JdbcDataSourceConnectionResult result = service.connect("ds-1");

    assertEquals("SUCCESS", definition.getLastConnectStatus());
    assertEquals("MockDB", definition.getDatabaseProductName());
    assertEquals("MockDB", result.databaseProductName());
    assertInstanceOf(SimpleDataSource.class, service.requireSimpleDataSource("ds-1"));
    verify(repository).save(definition);
  }

  @Test
  void connectShouldSetFailedStatusAndThrowWhenConnectionFails() {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:pg://.*$");
    JdbcDataSourceDefinition definition = buildDefinition("ds-1", "driver-1", "jdbc:pg://localhost/db");
    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager,
        List.of(new ThrowingFactory()), List.of()
    );

    assertThrows(IllegalStateException.class, () -> service.connect("ds-1"));
    assertEquals("FAILED", definition.getLastConnectStatus());
  }

  @Test
  void connectShouldThrowWhenIdIsBlank() {
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    assertThrows(IllegalArgumentException.class, () -> service.connect("  "));
  }

  @Test
  void connectShouldThrowWhenDefinitionNotFound() {
    when(repository.findActiveById("missing")).thenReturn(Optional.empty());
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    assertThrows(IllegalArgumentException.class, () -> service.connect("missing"));
  }

  @Test
  void getCachedSimpleDataSourceShouldReturnEmptyBeforeConnect() {
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    assertTrue(service.getCachedSimpleDataSource("ds-1").isEmpty());
  }

  @Test
  void disconnectShouldRemoveCachedDataSource() throws Exception {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:mysql://.*$");
    driver.setEnabled(true);
    JdbcDataSourceDefinition definition = buildDefinition("ds-1", "driver-1", "jdbc:mysql://localhost:3306/demo");
    definition.setEnabled(true);
    SimpleDataSource simpleDataSource = new SimpleDataSource(new SingleConnectionDataSource(connection));
    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(driverRepository.findAllByIds(any())).thenReturn(List.of(driver));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("MockDB");
    when(metaData.getDatabaseProductVersion()).thenReturn("1.0");
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager,
        List.of(new StubFactory(simpleDataSource)), List.of()
    );
    service.connect("ds-1");
    assertTrue(service.getCachedSimpleDataSource("ds-1").isPresent());

    service.disconnect("ds-1");

    assertTrue(service.getCachedSimpleDataSource("ds-1").isEmpty());
  }

  @Test
  void disconnectShouldDoNothingWhenIdIsNull() {
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    // should not throw
    service.disconnect(null);
    service.disconnect("  ");
  }

  @Test
  void requireSimpleDataSourceShouldThrowWhenDefinitionDisabled() {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:pg://.*$");
    driver.setEnabled(true);
    JdbcDataSourceDefinition definition = buildDefinition("ds-1", "driver-1", "jdbc:pg://localhost/db");
    definition.setEnabled(false);
    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager,
        List.of(new StubFactory(new SimpleDataSource(new EmptyDataSource()))), List.of()
    );
    assertThrows(IllegalStateException.class, () -> service.requireSimpleDataSource("ds-1"));
  }

  @Test
  void requireSimpleDataSourceShouldThrowWhenDriverDisabled() {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:pg://.*$");
    driver.setEnabled(false);
    JdbcDataSourceDefinition definition = buildDefinition("ds-1", "driver-1", "jdbc:pg://localhost/db");
    definition.setEnabled(true);
    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager,
        List.of(new StubFactory(new SimpleDataSource(new EmptyDataSource()))), List.of()
    );
    assertThrows(IllegalStateException.class, () -> service.requireSimpleDataSource("ds-1"));
  }

  @Test
  void createShouldThrowWhenCodeAlreadyExists() {
    JdbcDataSourceDefinition existing = buildDefinition("ds-existing", "driver-1", "jdbc:mysql://localhost/db");
    when(repository.findActiveByCode("demo")).thenReturn(Optional.of(existing));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    JdbcDataSourceDefinition definition = buildDefinition(null, "driver-1", "jdbc:mysql://localhost:3306/db");

    assertThrows(IllegalArgumentException.class, () -> service.create(definition));
  }

  @Test
  void createShouldThrowWhenNameIsEmpty() {
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setCode("code");
    definition.setDriverId("d1");
    definition.setJdbcUrl("jdbc:x://localhost/db");
    assertThrows(IllegalArgumentException.class, () -> service.create(definition));
  }

  @Test
  void createShouldThrowWhenDriverNotFound() {
    when(driverRepository.findActiveById("missing-driver")).thenReturn(Optional.empty());
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setName("demo");
    definition.setCode("demo");
    definition.setDriverId("missing-driver");
    definition.setJdbcUrl("jdbc:pg://localhost/db");

    assertThrows(IllegalArgumentException.class, () -> service.create(definition));
  }

  @Test
  void disconnectByDriverIdShouldCloseAllRelatedDataSources() throws Exception {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:mysql://.*$");
    driver.setEnabled(true);
    JdbcDataSourceDefinition def1 = buildDefinition("ds-1", "driver-1", "jdbc:mysql://localhost:3306/db1");
    def1.setEnabled(true);
    JdbcDataSourceDefinition def2 = buildDefinition("ds-2", "driver-1", "jdbc:mysql://localhost:3306/db2");
    SimpleDataSource ds1 = new SimpleDataSource(new SingleConnectionDataSource(connection));

    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(def1));
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(driverRepository.findAllByIds(any())).thenReturn(List.of(driver));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("MockDB");
    when(metaData.getDatabaseProductVersion()).thenReturn("1.0");
    when(repository.findAllActiveByDriverId("driver-1")).thenReturn(List.of(def1, def2));

    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager,
        List.of(new StubFactory(ds1)), List.of()
    );
    service.connect("ds-1");

    service.disconnectByDriverId("driver-1");

    assertTrue(service.getCachedSimpleDataSource("ds-1").isEmpty());
  }

  @Test
  void disconnectByDriverIdShouldDoNothingForNullDriverId() {
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    service.disconnectByDriverId(null);
    verify(repository, never()).findAllActiveByDriverId(any());
  }

  @Test
  void findActiveByIdShouldReturnDecoratedDefinition() {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:mysql://.*$");
    driver.setCode("mysql");
    driver.setName("MySQL");
    JdbcDataSourceDefinition definition = buildDefinition("ds-1", "driver-1", "jdbc:mysql://localhost/db");
    when(repository.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(driverRepository.findAllByIds(any())).thenReturn(List.of(driver));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );

    Optional<JdbcDataSourceDefinition> result = service.findActiveById("ds-1");

    assertTrue(result.isPresent());
    assertEquals("mysql", result.get().getDriverCode());
  }

  @Test
  void findActiveByIdShouldReturnEmptyWhenNotFound() {
    when(repository.findActiveById("unknown")).thenReturn(Optional.empty());
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    assertFalse(service.findActiveById("unknown").isPresent());
  }

  @Test
  void findActiveByCodeShouldReturnDecoratedDefinition() {
    JdbcDriverDefinition driver = buildDriver("driver-1", "^jdbc:pg://.*$");
    driver.setCode("pg");
    driver.setName("PG");
    JdbcDataSourceDefinition definition = buildDefinition("ds-1", "driver-1", "jdbc:pg://localhost/db");
    when(repository.findActiveByCode("ds-code")).thenReturn(Optional.of(definition));
    when(driverRepository.findAllByIds(any())).thenReturn(List.of(driver));
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );

    Optional<JdbcDataSourceDefinition> result = service.findActiveByCode("ds-code");

    assertTrue(result.isPresent());
    assertEquals("pg", result.get().getDriverCode());
  }

  @Test
  void listEnabledDefinitionsShouldReturnOnlyEnabledSortedByCode() {
    JdbcDataSourceDefinition ds1 = buildDefinition("ds-1", "driver-1", "jdbc:pg://localhost/b");
    ds1.setCode("beta");
    ds1.setEnabled(true);
    JdbcDataSourceDefinition ds2 = buildDefinition("ds-2", "driver-1", "jdbc:pg://localhost/a");
    ds2.setCode("alpha");
    ds2.setEnabled(true);
    JdbcDataSourceDefinition ds3 = buildDefinition("ds-3", "driver-1", "jdbc:pg://localhost/d");
    ds3.setCode("disabled");
    ds3.setEnabled(false);
    when(repository.findAllActive()).thenReturn(List.of(ds1, ds2, ds3));
    when(driverRepository.findAllByIds(any())).thenReturn(List.of());
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );

    List<JdbcDataSourceDefinition> result = service.listEnabledDefinitions();

    assertEquals(2, result.size());
    assertEquals("alpha", result.get(0).getCode());
    assertEquals("beta", result.get(1).getCode());
  }

  @Test
  void removeByIdsShouldReturnEarlyForNullOrEmpty() {
    final JdbcDataSourceDefinitionServiceImpl service = new JdbcDataSourceDefinitionServiceImpl(
        repository, detailsProviderService, driverRepository, artifactManager, List.of(), List.of()
    );
    // No-ops — should not throw or interact with any repository
    service.removeByIds(List.of());
    service.removeByIds(null);
  }

  // ---- static helpers ----

  private static JdbcDriverDefinition buildDriver(final String id, final String urlPattern) {
    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId(id);
    driver.setCode(id);
    driver.setJdbcUrlPattern(urlPattern);
    return driver;
  }

  private static JdbcDataSourceDefinition buildDefinition(
      final String id,
      final String driverId,
      final String jdbcUrl
  ) {
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId(id);
    definition.setName("demo");
    definition.setCode("demo");
    definition.setDriverId(driverId);
    definition.setJdbcUrl(jdbcUrl);
    return definition;
  }

  private record ThrowingFactory() implements JdbcManagedDataSourceFactory {
    @Override
    public boolean supports(final JdbcDriverDefinition driver) {
      return true;
    }

    @Override
    public SimpleDataSource create(final JdbcDriverDefinition driver, final JdbcDataSourceDefinition dataSource) {
      throw new RuntimeException("Connection refused");
    }
  }

  private record StubFactory(SimpleDataSource simpleDataSource) implements JdbcManagedDataSourceFactory {
    @Override
    public boolean supports(final JdbcDriverDefinition driver) {
      return true;
    }

    @Override
    public SimpleDataSource create(final JdbcDriverDefinition driver, final JdbcDataSourceDefinition dataSource) {
      return simpleDataSource;
    }
  }

  private record SingleConnectionDataSource(Connection connection) implements DataSource {
    @Override
    public Connection getConnection() {
      return connection;
    }

    @Override
    public Connection getConnection(final String username, final String password) {
      return connection;
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(final java.io.PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(final int seconds) {
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(final Class<T> iface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) {
      return false;
    }
  }

  private static final class EmptyDataSource implements DataSource {
    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Connection getConnection(final String username, final String password) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(final java.io.PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(final int seconds) {
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(final Class<T> iface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) {
      return false;
    }
  }
}
