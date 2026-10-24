package org.simplepoint.plugin.dna.core.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URL;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.configuration.ManagedJdbcPoolProperties;

class DefaultJdbcManagedDataSourceFactoryTest {
  private final JdbcDriverArtifactManager artifacts = mock(JdbcDriverArtifactManager.class);

  @Test
  void connectionBudgetIsEnforcedAndClosingWrapperClosesActualPool() throws Exception {
    var limits = new ManagedJdbcPoolProperties();
    limits.setMaximumPoolSize(1);
    limits.setConnectionTimeoutMs(250);
    var driver = new JdbcDriverDefinition();
    driver.setDriverClassName("org.h2.Driver");
    when(artifacts.ensureDownloaded(driver)).thenReturn(driver);
    when(artifacts.resolveArtifactUrls(driver)).thenReturn(new URL[0]);
    var definition = new JdbcDataSourceDefinition();
    definition.setJdbcUrl("jdbc:h2:mem:managed_pool_budget");
    definition.setUsername("sa");
    definition.setPassword("");
    definition.setConnectionProperties("MODE=PostgreSQL");
    var originalLoader = Thread.currentThread().getContextClassLoader();
    var source = new DefaultJdbcManagedDataSourceFactory(artifacts, limits).create(driver, definition);
    HikariDataSource pool = source.unwrap(HikariDataSource.class);
    try (source) {
      assertThat(pool.getMinimumIdle()).isZero();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
      assertThat(pool.getDataSourceProperties()).containsEntry("MODE", "PostgreSQL");
      try (var connection = source.getConnection()) {
        assertThat(connection.isValid(1)).isTrue();
        assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(originalLoader);
      }
      try (var connection = source.getConnection()) {
        assertThat(connection.isValid(1)).isTrue();
      }
    }
    assertThat(pool.isClosed()).isTrue();
    assertThatThrownBy(source::getConnection).isInstanceOf(SQLException.class);
    source.close();
    assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(originalLoader);
  }

  @Test
  void invalidBudgetFailsBeforeArtifactsOrConnectionsAreOpened() {
    var limits = new ManagedJdbcPoolProperties();
    limits.setMaximumPoolSize(0);
    assertThatThrownBy(() -> new DefaultJdbcManagedDataSourceFactory(artifacts, limits))
        .isInstanceOf(IllegalArgumentException.class);
    limits.setMaximumPoolSize(2);
    limits.setMinimumIdle(3);
    assertThatThrownBy(() -> new DefaultJdbcManagedDataSourceFactory(artifacts, limits))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(artifacts);
  }
}
