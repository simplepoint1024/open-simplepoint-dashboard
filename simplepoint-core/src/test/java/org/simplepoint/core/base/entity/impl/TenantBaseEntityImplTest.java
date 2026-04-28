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

class TenantBaseEntityImplTest {

  private static class ConcreteTenantEntity extends TenantBaseEntityImpl<String> {
  }

  @Test
  void prePersist_defaultsTenantIdWhenNull() {
    ConcreteTenantEntity entity = new ConcreteTenantEntity();
    assertThat(entity.getTenantId()).isNull();

    entity.prePersist();

    assertThat(entity.getTenantId()).isEqualTo("default");
    assertThat(entity.getCreatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
  }

  @Test
  void prePersist_doesNotOverwriteExistingTenantId() {
    ConcreteTenantEntity entity = new ConcreteTenantEntity();
    entity.setTenantId("tenant-123");

    entity.prePersist();

    assertThat(entity.getTenantId()).isEqualTo("tenant-123");
  }

  @Test
  void settersAndGettersWork() {
    ConcreteTenantEntity entity = new ConcreteTenantEntity();
    entity.setId("id-1");
    entity.setTenantId("t1");
    entity.setCreatedBy("admin");

    assertThat(entity.getId()).isEqualTo("id-1");
    assertThat(entity.getTenantId()).isEqualTo("t1");
    assertThat(entity.getCreatedBy()).isEqualTo("admin");
  }
}
