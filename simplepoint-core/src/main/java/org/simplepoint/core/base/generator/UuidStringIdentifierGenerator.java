package org.simplepoint.core.base.generator;

import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;

/**
 * UUID-based identifier generator for JPA entities.
 *
 * <p>This generator produces unique string identifiers using UUIDs,
 * ensuring that each generated ID is globally unique.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
public class UuidStringIdentifierGenerator implements BeforeExecutionGenerator {

  @Override
  public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
    // 如果已有值就用已有值，否则生成新的
    return currentValue != null ? currentValue : UUID.randomUUID().toString();
  }

  @Override
  public EnumSet<EventType> getEventTypes() {
    // 告诉 Hibernate 在 INSERT 时调用这个生成器
    return EnumSet.of(EventType.INSERT);
  }

  /**
   * Allows callers to pin a stable UUID before persistence.
   *
   * <p>Invocation ledgers and durable execution traces use the same UUID
   * across process boundaries. Hibernate 7 requires custom generators to
   * opt in explicitly before accepting such identifiers.</p>
   */
  @Override
  public boolean allowAssignedIdentifiers() {
    return true;
  }
}
