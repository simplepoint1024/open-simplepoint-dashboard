package org.simplepoint.core.query;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.simplepoint.core.lambda.Property;

/**
 * Conditions Builder for building query conditions.
 *
 * @param <T> the type of the example object
 */
public class ConditionsBuilder<T> {

  public static final String PREFIX_LIKE = "like";
  public static final String PREFIX_IN = "in";
  public static final String PREFIX_NOT_IN = "not:in";
  public static final String PREFIX_NOT_LIKE = "not:like";
  public static final String PREFIX_BETWEEN = "between";
  public static final String PREFIX_NOT_BETWEEN = "not:between";
  public static final String PREFIX_EQUALS = "equals";
  public static final String PREFIX_NOT_EQUALS = "not:equals";
  public static final String PREFIX_THAN_GREATER = "than:greater";
  public static final String PREFIX_THAN_LESS = "than:less";
  public static final String PREFIX_THAN_EQUAL_GREATER = "than:equal:greater";
  public static final String PREFIX_THAN_EQUAL_LESS = "than:equal:less";
  public static final String PREFIX_IS_NULL = "is:null";
  public static final String PREFIX_IS_NOT_NULL = "is:not:null";

  private final T example;

  private final Map<String, String> attributes = new HashMap<>();

  /**
   * Constructor for ConditionsBuilder.
   *
   * @param example an example object of type T
   */
  public ConditionsBuilder(T example) {
    this.example = example;
  }

  /**
   * Create a new ConditionsBuilder instance with an example object.
   *
   * @param example an example object of type T
   * @param <T>     the type of the example object
   * @return a new ConditionsBuilder instance
   */
  public static <T> ConditionsBuilder<T> of(T example) {
    return new ConditionsBuilder<>(example);
  }

  /**
   * Create a new ConditionsBuilder instance without an example object.
   *
   * @param <T> the type of the example object
   * @return a new ConditionsBuilder instance
   */
  public static <T> ConditionsBuilder<T> of() {
    return new ConditionsBuilder<>(null);
  }

  /**
   * Build the conditions map.
   *
   * @return a map of conditions
   */
  public Map<String, String> build() {
    return attributes;
  }

  /**
   * Get the example object.
   *
   * @return the example object of type T
   */
  public ConditionsBuilder<T> like(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_LIKE);
    return this;
  }

  /**
   * Add a 'like' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> like(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_LIKE, value);
    return this;
  }

  /**
   * Add a 'not like' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notLike(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_NOT_LIKE);
    return this;
  }

  /**
   * Add a 'not like' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notLike(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_NOT_LIKE, value);
    return this;
  }

  /**
   * Add an 'in' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> in(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_IN);
    return this;
  }

  /**
   * Add an 'in' condition to the builder with specific values.
   *
   * @param getter a method reference to the property
   * @param values the values to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> in(Property<T, ?> getter, String... values) {
    String joinedValues = String.join(",", values);
    putAttribute(getter, PREFIX_IN, joinedValues);
    return this;
  }

  /**
   * Add an 'in' condition to the builder with specific values.
   *
   * @param getter a method reference to the property
   * @param values the values to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> in(Property<T, ?> getter, Collection<String> values) {
    String joinedValues = String.join(",", values);
    putAttribute(getter, PREFIX_IN, joinedValues);
    return this;
  }

  /**
   * Add a 'not in' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notIn(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_NOT_IN);
    return this;
  }

  /**
   * Add a 'not in' condition to the builder with specific values.
   *
   * @param getter a method reference to the property
   * @param values the values to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notIn(Property<T, ?> getter, String... values) {
    String joinedValues = String.join(",", values);
    putAttribute(getter, PREFIX_NOT_IN, joinedValues);
    return this;
  }

  /**
   * Add an 'equals' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> equals(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_EQUALS);
    return this;
  }

  /**
   * Add an 'equals' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> equals(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_EQUALS, value);
    return this;
  }

  /**
   * Add a 'not equals' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notEquals(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_NOT_EQUALS);
    return this;
  }

  /**
   * Add a 'not equals' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notEquals(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_NOT_EQUALS, value);
    return this;
  }

  /**
   * Add an 'is null' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> isNull(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_IS_NULL);
    return this;
  }

  /**
   * Add an 'is not null' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> isNotNull(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_IS_NOT_NULL);
    return this;
  }

  /**
   * Add a 'greater than' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> greaterThan(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_THAN_GREATER);
    return this;
  }

  /**
   * Add a 'greater than' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> greaterThan(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_THAN_GREATER, value);
    return this;
  }

  /**
   * Add a 'less than' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> lessThan(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_THAN_LESS);
    return this;
  }

  /**
   * Add a 'less than' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> lessThan(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_THAN_LESS, value);
    return this;
  }

  /**
   * Add a 'greater than or equal' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> greaterThanOrEqual(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_THAN_EQUAL_GREATER);
    return this;
  }

  /**
   * Add a 'greater than or equal' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> greaterThanOrEqual(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_THAN_EQUAL_GREATER, value);
    return this;
  }

  /**
   * Add a 'less than or equal' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> lessThanOrEqual(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_THAN_EQUAL_LESS);
    return this;
  }

  /**
   * Add a 'less than or equal' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> lessThanOrEqual(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_THAN_EQUAL_LESS, value);
    return this;
  }

  /**
   * Add a 'between' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> between(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_BETWEEN);
    return this;
  }

  /**
   * Add a 'between' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> between(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_BETWEEN, value);
    return this;
  }

  /**
   * Add a 'not between' condition to the builder.
   *
   * @param getter a method reference to the property
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notBetween(Property<T, ?> getter) {
    putAttribute(getter, PREFIX_NOT_BETWEEN);
    return this;
  }

  /**
   * Add a 'not between' condition to the builder with a specific value.
   *
   * @param getter a method reference to the property
   * @param value  the value to compare
   * @return the updated ConditionsBuilder instance
   */
  public ConditionsBuilder<T> notBetween(Property<T, ?> getter, String value) {
    putAttribute(getter, PREFIX_NOT_BETWEEN, value);
    return this;
  }

  /**
   * Add a custom prefix condition to the builder.
   *
   * @param getter a method reference to the property
   * @param prefix the prefix to use for the condition
   */
  private void putAttribute(Property<T, ?> getter, String prefix) {
    String fieldName = resolveFieldName(getter);
    String fieldValue = resolveFieldValue(getter);
    attributes.put(fieldName, prefix + ":" + fieldValue);
  }

  /**
   * Add a custom prefix condition with a specific value to the builder.
   *
   * @param getter a method reference to the property
   * @param prefix the prefix to use for the condition
   * @param value  the value to use for the condition
   */
  private void putAttribute(Property<T, ?> getter, String prefix, String value) {
    String fieldName = resolveFieldName(getter);
    attributes.put(fieldName, prefix + ":" + value);
  }

  /**
   * Resolve the field value from the method reference.
   *
   * @param getter a method reference to the property
   * @return the field value as a string
   */
  String resolveFieldValue(Property<T, ?> getter) {
    try {
      Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
      writeReplace.setAccessible(true);
      SerializedLambda sl = (SerializedLambda) writeReplace.invoke(getter);
      String method = sl.getImplMethodName();
      if (method.startsWith("get") && method.length() > 3) {
        String fieldName = decapitalize(method.substring(3));
        Field declaredField = example.getClass().getDeclaredField(fieldName);
        declaredField.setAccessible(true);
        Object value = declaredField.get(example);
        return String.valueOf(value);
      }
      if (method.startsWith("is") && method.length() > 2) {
        String fieldName = decapitalize(method.substring(2));
        Field declaredField = example.getClass().getDeclaredField(fieldName);
        declaredField.setAccessible(true);
        Object value = declaredField.get(example);
        return String.valueOf(value);
      }
      return null;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to resolve field value from method reference", e);
    }
  }

  /**
   * Resolve the field name from the method reference.
   *
   * @param getter a method reference to the property
   * @return the field name as a string
   */
  <R> String resolveFieldName(Property<T, R> getter) {
    try {
      Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
      writeReplace.setAccessible(true);
      SerializedLambda sl = (SerializedLambda) writeReplace.invoke(getter);
      String method = sl.getImplMethodName();
      if (method.startsWith("get") && method.length() > 3) {
        return decapitalize(method.substring(3));
      }
      if (method.startsWith("is") && method.length() > 2) {
        return decapitalize(method.substring(2));
      }
      return method;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to resolve field name from method reference", e);
    }
  }

  /**
   * Decapitalize the first character of a string.
   *
   * @param s the input string
   * @return the decapitalized string
   */
  String decapitalize(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    char[] chars = s.toCharArray();
    chars[0] = Character.toLowerCase(chars[0]);
    return new String(chars);
  }
}
