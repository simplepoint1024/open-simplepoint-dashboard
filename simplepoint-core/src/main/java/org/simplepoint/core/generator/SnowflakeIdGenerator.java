/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.generator;

import cn.hutool.core.lang.generator.SnowflakeGenerator;
import java.util.EnumSet;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.simplepoint.core.properties.CoreProperties;

/**
 * An implementation of the IdentifierGenerator interface.
 * This class uses the SnowflakeGenerator to generate unique identifiers based
 * on worker and datacenter IDs configured in CoreProperties.
 */
public class SnowflakeIdGenerator implements BeforeExecutionGenerator {

  // Static instance of SnowflakeGenerator initialized with worker ID and datacenter ID
  private static final SnowflakeGenerator generator =
      new SnowflakeGenerator(CoreProperties.workerId, CoreProperties.dcId);

  @Override
  public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
    return generator.next();
  }

  @Override
  public EnumSet<EventType> getEventTypes() {
    return EnumSet.of(EventType.INSERT);
  }
}

