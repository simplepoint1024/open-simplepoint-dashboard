package org.simplepoint.data.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.query.EscapeCharacter;

class AttributeMatchersTest {

  private static final String TEST_KEY = "__test_custom_matcher__";

  @AfterEach
  void cleanup() {
    AttributeMatchers.unregisterAttributeMatcher(TEST_KEY);
  }

  @Test
  void defaultMatchersRegistered() {
    assertThat(AttributeMatchers.getAttributeMatcher("like")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("in")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("not:in")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("between")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("equals")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("not:equals")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("than:greater")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("than:less")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("is:null")).isNotNull();
    assertThat(AttributeMatchers.getAttributeMatcher("is:not:null")).isNotNull();
  }

  @Test
  void register_and_get_customMatcher() {
    AttributeMatcher custom = new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
      }
    };
    AttributeMatchers.registerAttributeMatcher(TEST_KEY, custom);
    assertThat(AttributeMatchers.getAttributeMatcher(TEST_KEY)).isSameAs(custom);
  }

  @Test
  void unregister_removesCustomMatcher() {
    AttributeMatcher custom = new AttributeMatcher() {
      @Override
      public <S> void match(String name, String value, Root<S> root, CriteriaQuery<?> query,
                            CriteriaBuilder criteriaBuilder, EscapeCharacter character,
                            List<Predicate> predicates) throws Exception {
      }
    };
    AttributeMatchers.registerAttributeMatcher(TEST_KEY, custom);
    AttributeMatchers.unregisterAttributeMatcher(TEST_KEY);
    assertThat(AttributeMatchers.getAttributeMatcher(TEST_KEY)).isNull();
  }

  @Test
  void get_unknownKey_returnsNull() {
    assertThat(AttributeMatchers.getAttributeMatcher("__nonexistent__")).isNull();
  }
}

