package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class DefaultTableOrderTest {
  record Ordinary(String id, Instant createdAt, Instant updatedAt) {}
  record Ordered(String id, Integer sort, Instant createdAt) {}

  @Test void creationNotModificationDeterminesNewest() {
    assertThat(DefaultTableOrder.forModel(Ordinary.class, name -> DefaultTableOrder.exists(Ordinary.class, name)))
        .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  @Test void OrdinalAndAuthorizationTakePrecedence() {
    assertThat(DefaultTableOrder.forModel(Ordered.class, name -> DefaultTableOrder.exists(Ordered.class, name)))
        .isEqualTo(Sort.by("sort"));
    assertThat(DefaultTableOrder.forModel(Ordered.class, name -> Set.of("id").contains(name))).isEqualTo(Sort.unsorted());
  }
}
