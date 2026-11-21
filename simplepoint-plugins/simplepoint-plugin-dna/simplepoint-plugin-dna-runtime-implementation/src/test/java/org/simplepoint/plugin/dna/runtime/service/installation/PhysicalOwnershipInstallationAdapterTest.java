package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;

class PhysicalOwnershipInstallationAdapterTest {
  @Test
  void requestAndWorkerContextsAreRejectedBeforeConnectingToStorage() {
    var source = mock(DataSource.class);
    var adapter = new PhysicalOwnershipInstallationAdapter(source);
    List<Runnable> contexts =
        List.of(
            () ->
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    mock(RequestAttributes.class)),
            () ->
                RequestContextHolder.setContext(
                    RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
                    mock(AuthorizationContext.class)),
            () ->
                RequestContextHolder.setContext(RequestContextHolder.TENANT_CONTEXT_KEY, "tenant"),
            () -> RequestContextHolder.setContext(RequestContextHolder.USER_CONTEXT_KEY, "user"),
            () ->
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                            "installer", null, List.of())),
            () -> TransactionSynchronizationManager.setActualTransactionActive(true));
    for (Runnable context : contexts) {
      try {
        context.run();
        assertThatThrownBy(adapter::inventory)
            .hasMessage("DNA_OWNERSHIP_INVENTORY_OFFLINE_REQUIRED");
        verifyNoInteractions(source);
      } finally {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
        RequestContextHolder.clearContext(RequestContextHolder.TENANT_CONTEXT_KEY);
        RequestContextHolder.clearContext(RequestContextHolder.USER_CONTEXT_KEY);
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
    }
  }

  @Test
  void inheritedTransactionIsNotReadOrCommitted() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    assertThatThrownBy(new PhysicalOwnershipInstallationAdapter(source)::inventory)
        .hasMessage("DNA_OWNERSHIP_INVENTORY_CONTROL_STORE");
    verify(connection, never()).commit();
    verify(connection, never()).getMetaData();
    verify(connection).close();
  }

  @Test
  void connectionFailureRetainsDiagnosticsWithoutCredentialBearingMessages() throws Exception {
    var source = mock(DataSource.class);
    when(source.getConnection())
        .thenThrow(
            new SQLException("jdbc:postgresql://private/?password=must-not-escape", "08001", 19));
    assertThatThrownBy(new PhysicalOwnershipInstallationAdapter(source)::inventory)
        .hasMessage("DNA_OWNERSHIP_INVENTORY_UNAVAILABLE")
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).isInstanceOf(SQLException.class);
              var diagnostic = (SQLException) failure.getCause();
              assertThat(diagnostic.getSQLState()).isEqualTo("08001");
              assertThat(diagnostic.getErrorCode()).isEqualTo(19);
              assertThat(diagnostic.getMessage()).doesNotContain("must-not-escape", "jdbc:");
              assertThat(diagnostic.getCause()).isNull();
            });
  }
}
