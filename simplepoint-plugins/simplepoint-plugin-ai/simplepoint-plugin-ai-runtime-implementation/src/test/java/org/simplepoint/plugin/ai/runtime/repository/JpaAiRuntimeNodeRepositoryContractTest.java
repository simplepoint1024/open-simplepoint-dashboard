package org.simplepoint.plugin.ai.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class JpaAiRuntimeNodeRepositoryContractTest {

  @Test
  void expiredNodeScanUsesNonBlockingPessimisticWriteLock()
      throws ReflectiveOperationException {
    Method method = JpaAiRuntimeNodeRepository.class.getMethod(
        "findExpiredNodes",
        Instant.class,
        List.class
    );

    Annotation lock = requireAnnotation(
        method,
        "org.springframework.data.jpa.repository.Lock"
    );
    Object lockMode = lock.annotationType().getMethod("value").invoke(lock);
    assertEquals(LockModeType.PESSIMISTIC_WRITE, lockMode);

    Annotation queryHints = requireAnnotation(
        method,
        "org.springframework.data.jpa.repository.QueryHints"
    );
    QueryHint[] hints = (QueryHint[]) queryHints.annotationType()
        .getMethod("value")
        .invoke(queryHints);
    QueryHint lockTimeout = Arrays.stream(hints)
        .filter(hint -> "jakarta.persistence.lock.timeout".equals(hint.name()))
        .findFirst()
        .orElseThrow();
    assertEquals("-2", lockTimeout.value());
  }

  private static Annotation requireAnnotation(
      final Method method,
      final String annotationType
  ) {
    return Arrays.stream(method.getDeclaredAnnotations())
        .filter(annotation -> annotationType.equals(
            annotation.annotationType().getName()
        ))
        .findFirst()
        .orElseThrow();
  }
}
