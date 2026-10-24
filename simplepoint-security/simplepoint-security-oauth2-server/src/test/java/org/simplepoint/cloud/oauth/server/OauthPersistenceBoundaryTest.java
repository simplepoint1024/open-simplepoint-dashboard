package org.simplepoint.cloud.oauth.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Browser adapters must delegate persistence and transactions to their owning domain service. */
class OauthPersistenceBoundaryTest {
  @Test
  void browserAdapterClassesCannotAcquireUnownedRepositoriesOrTransactions() throws Exception {
    var scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
            return definition.getMetadata().isIndependent();
          }
        };
    scanner.addIncludeFilter((metadata, factory) -> true);
    var candidates = scanner.findCandidateComponents("org.simplepoint.cloud.oauth.server");
    assertTrue(candidates.size() > 20);
    for (var definition : candidates) {
      Class<?> type =
          Class.forName(definition.getBeanClassName(), false, getClass().getClassLoader());
      assertNoTransaction(type);
      for (var field : type.getDeclaredFields()) {
        assertNoResource(field.getType());
      }
      for (var constructor : type.getDeclaredConstructors()) {
        Arrays.stream(constructor.getParameterTypes())
            .forEach(OauthPersistenceBoundaryTest::assertNoResource);
      }
      for (var method : type.getDeclaredMethods()) {
        assertNoTransaction(method);
        assertNoResource(method.getReturnType());
        Arrays.stream(method.getParameterTypes())
            .forEach(OauthPersistenceBoundaryTest::assertNoResource);
      }
    }
  }

  private static void assertNoTransaction(AnnotatedElement element) {
    assertFalse(
        AnnotatedElementUtils.hasAnnotation(element, Transactional.class), element.toString());
  }

  private static void assertNoResource(Class<?> type) {
    for (Class<?> forbidden :
        List.of(
            Repository.class,
            org.simplepoint.api.base.BaseRepository.class,
            EntityManager.class,
            EntityManagerFactory.class,
            DataSource.class,
            JdbcOperations.class,
            PlatformTransactionManager.class)) {
      assertFalse(forbidden.isAssignableFrom(type), type.getName());
    }
  }
}
