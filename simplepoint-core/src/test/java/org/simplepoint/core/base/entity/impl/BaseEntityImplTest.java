/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.base.entity.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class BaseEntityImplTest {

  private static class ConcreteEntity extends BaseEntityImpl<String> {
  }

  @Test
  void settersAndGettersWork() {
    ConcreteEntity entity = new ConcreteEntity();
    entity.setId("abc");
    entity.setCreatedBy("user1");
    entity.setUpdatedBy("user2");

    assertThat(entity.getId()).isEqualTo("abc");
    assertThat(entity.getCreatedBy()).isEqualTo("user1");
    assertThat(entity.getUpdatedBy()).isEqualTo("user2");
  }

  @Test
  void prePersist_setsCreatedAtAndUpdatedAtWhenNull() {
    ConcreteEntity entity = new ConcreteEntity();
    assertThat(entity.getCreatedAt()).isNull();
    assertThat(entity.getUpdatedAt()).isNull();

    entity.prePersist();

    assertThat(entity.getCreatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    assertThat(entity.getUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
  }

  @Test
  void prePersist_doesNotOverwriteExistingTimestamps() {
    ConcreteEntity entity = new ConcreteEntity();
    Instant original = Instant.parse("2020-01-01T00:00:00Z");
    entity.setCreatedAt(original);
    entity.setUpdatedAt(original);

    entity.prePersist();

    assertThat(entity.getCreatedAt()).isEqualTo(original);
    assertThat(entity.getUpdatedAt()).isEqualTo(original);
  }

  @Test
  void preUpdate_refreshesUpdatedAt() {
    ConcreteEntity entity = new ConcreteEntity();
    entity.setUpdatedAt(Instant.parse("2020-01-01T00:00:00Z"));

    entity.preUpdate();

    assertThat(entity.getUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
  }

  @Test
  void preRemove_setsDeletedAt() {
    ConcreteEntity entity = new ConcreteEntity();
    assertThat(entity.getDeletedAt()).isNull();

    entity.preRemove();

    assertThat(entity.getDeletedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
  }

  @Test
  void createOrgDeptId_setterAndGetter() {
    ConcreteEntity entity = new ConcreteEntity();
    entity.setCreateOrgDeptId("dept-99");
    assertThat(entity.getCreateOrgDeptId()).isEqualTo("dept-99");
  }
}
