package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.core.api.repository.UserPreferenceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UserPreferenceService;
import org.simplepoint.security.entity.UserPreference;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class UserPreferenceServiceImplTest {
  private UserPreferenceRepository repository;
  private UserPreferenceService service;

  @BeforeEach
  void setup() {
    repository = mock(UserPreferenceRepository.class);
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:user-preferences", "sa", "");
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(new UserPreferenceServiceImpl(repository));
    proxy.addAdvice(interceptor);
    service = (UserPreferenceService) proxy.getProxy();
    context("user-a");
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  private void context(String userId) {
    var context = new AuthorizationContext();
    context.setUserId(userId);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private void assertReadTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
  }

  @Test
  void readAndLobMaterializationRunInOneReadOnlyTransaction() {
    var preference = mock(UserPreference.class);
    when(repository.findByUserIdAndPreferenceKey("user-a", "home.v1.platform")).thenAnswer(invocation -> {
      assertReadTransaction();
      return Optional.of(preference);
    });
    when(preference.getPreferenceValue()).thenAnswer(invocation -> {
      assertReadTransaction();
      return "{\"version\":1,\"groups\":[]}";
    });
    assertThat(service.getPreference("home.v1.platform")).contains("{\"version\":1,\"groups\":[]}");
  }

  @Test
  void missingPreferenceRemainsEmptyAndReadsAreIsolatedByCurrentUser() {
    when(repository.findByUserIdAndPreferenceKey("user-a", "home.v1.platform")).thenReturn(Optional.empty());
    when(repository.findByUserIdAndPreferenceKey("user-b", "home.v1.platform")).thenReturn(Optional.empty());
    assertThat(service.getPreference("home.v1.platform")).isEmpty();
    context("user-b");
    assertThat(service.getPreference("home.v1.platform")).isEmpty();
    verify(repository).findByUserIdAndPreferenceKey("user-a", "home.v1.platform");
    verify(repository).findByUserIdAndPreferenceKey("user-b", "home.v1.platform");
  }

  @Test
  void readFailureIsNotTurnedIntoAnEmptyPreference() {
    when(repository.findByUserIdAndPreferenceKey("user-a", "home.v1.platform"))
        .thenThrow(new IllegalStateException("storage unavailable"));
    assertThatThrownBy(() -> service.getPreference("home.v1.platform"))
        .isInstanceOf(IllegalStateException.class).hasMessage("storage unavailable");
  }

  @Test
  void writesRetainWritableTransactionsAndPreserveExistingValueUntilSave() {
    var preference = new UserPreference();
    preference.setPreferenceValue("original");
    when(repository.findByUserIdAndPreferenceKey("user-a", "home.v1.platform")).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
      return Optional.of(preference);
    });
    service.setPreference("home.v1.platform", "updated");
    assertThat(preference.getPreferenceValue()).isEqualTo("updated");
    assertThat(preference.getUserId()).isEqualTo("user-a");
    verify(repository).save(preference);
  }
}
