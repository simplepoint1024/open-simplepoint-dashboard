package org.simplepoint.core.base.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UuidStringIdentifierGeneratorTest {

  private UuidStringIdentifierGenerator generator;
  private SharedSessionContractImplementor session;

  @BeforeEach
  void setUp() {
    generator = new UuidStringIdentifierGenerator();
    session = mock(SharedSessionContractImplementor.class);
  }

  @Test
  void generate_withNullCurrentValue_generatesUuid() {
    Object result = generator.generate(session, new Object(), null, EventType.INSERT);
    assertThat(result).isNotNull();
    assertThat(result.toString()).matches(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  @Test
  void generate_withExistingValue_returnsExistingValue() {
    String existing = "my-existing-id";
    Object result = generator.generate(session, new Object(), existing, EventType.INSERT);
    assertThat(result).isEqualTo(existing);
  }

  @Test
  void generate_consecutiveCalls_producesUniqueIds() {
    Object id1 = generator.generate(session, new Object(), null, EventType.INSERT);
    Object id2 = generator.generate(session, new Object(), null, EventType.INSERT);
    assertThat(id1).isNotEqualTo(id2);
  }

  @Test
  void getEventTypes_returnsInsertOnly() {
    EnumSet<EventType> eventTypes = generator.getEventTypes();
    assertThat(eventTypes).containsExactly(EventType.INSERT);
  }
}
