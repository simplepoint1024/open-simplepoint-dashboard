package org.simplepoint.plugin.dna.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
