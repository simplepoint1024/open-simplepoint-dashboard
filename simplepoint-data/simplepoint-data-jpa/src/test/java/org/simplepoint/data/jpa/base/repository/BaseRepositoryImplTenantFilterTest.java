package org.simplepoint.data.jpa.base.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletRequestAttributes;

class BaseRepositoryImplTenantFilterTest {

  @AfterEach
  void tearDown() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void enableTenantFilter_ignoresNonTenantEntity() {
    final EntityManager entityManager = entityManager();

    repository(NonTenantEntity.class, entityManager).enableTenantFilter();

    verify(entityManager, never()).unwrap(Session.class);
  }

  @Test
  void enableTenantFilter_rejectsTenantEntityWithoutContext() {
    EntityManager entityManager = entityManager();

    assertThatThrownBy(() -> repository(TenantEntity.class, entityManager).enableTenantFilter())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Tenant-aware repository operation requires an active tenant context");

    verify(entityManager, never()).unwrap(Session.class);
  }

  @Test
  void enableTenantFilter_allowsPlatformAdministratorWithoutTenant() {
    AuthorizationContext context = new AuthorizationContext();
    context.setIsAdministrator(true);
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    setRequestContext(context);

    EntityManager entityManager = entityManager();
    assertThatCode(() -> repository(TenantEntity.class, entityManager).enableTenantFilter())
        .doesNotThrowAnyException();

    verify(entityManager, never()).unwrap(Session.class);
  }

  @Test
  void enableTenantFilter_enablesHibernateFilterWhenTenantPresent() {
    EntityManager entityManager = entityManager();
    Session session = mock(Session.class);
    Filter filter = mock(Filter.class);
    when(entityManager.unwrap(Session.class)).thenReturn(session);
    when(session.enableFilter("tenantFilter")).thenReturn(filter);
    when(filter.setParameter("tenantId", "tenant-a")).thenReturn(filter);
    AuthorizationContext context = new AuthorizationContext();
    context.setAttributes(Map.of("X-Tenant-Id", " tenant-a "));
    setRequestContext(context);

    repository(TenantEntity.class, entityManager).enableTenantFilter();

    verify(session).enableFilter("tenantFilter");
    verify(filter).setParameter("tenantId", "tenant-a");
  }

  @SuppressWarnings("unchecked")
  private <T extends BaseEntityImpl<String>> BaseRepositoryImpl<T, String> repository(
      Class<T> domainClass,
      EntityManager entityManager
  ) {
    JpaEntityInformation<T, ?> entityInformation = mock(JpaEntityInformation.class);
    when(entityInformation.getJavaType()).thenReturn(domainClass);
    when(entityInformation.getEntityName()).thenReturn(domainClass.getSimpleName());
    return new BaseRepositoryImpl<>(entityInformation, entityManager);
  }

  private EntityManager entityManager() {
    EntityManager entityManager = mock(EntityManager.class);
    EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
    when(entityManager.getEntityManagerFactory()).thenReturn(entityManagerFactory);
    return entityManager;
  }

  private void setRequestContext(AuthorizationContext context) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(request));
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static class TenantEntity extends TenantBaseEntityImpl<String> {
  }

  private static class NonTenantEntity extends BaseEntityImpl<String> {
  }
}
