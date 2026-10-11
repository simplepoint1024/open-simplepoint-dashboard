package org.simplepoint.core.base;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Reject implicit database rounding/overflow after field-policy merging; metadata is cached per entity class. */
public final class DecimalColumnPolicy {
  private DecimalColumnPolicy() {}
  private record Rule(Field field, int scale, BigDecimal maximum, BigDecimal quantum) {}
  private static final ClassValue<List<Rule>> RULES = new ClassValue<>() {
    @Override protected List<Rule> computeValue(Class<?> type) {
      var rules = new ArrayList<Rule>();
      for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          var column = field.getAnnotation(Column.class);
          if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(jakarta.persistence.Transient.class)
              || field.getType() != BigDecimal.class || column == null || column.precision() <= 0) continue;
          int precision = column.precision(), scale = column.scale();
          if (precision > 1000 || Math.abs((long) scale) > 1000 || !field.trySetAccessible())
            throw new IllegalStateException("无效的十进制字段约束: " + field.getName());
          var quantum = BigDecimal.ONE.scaleByPowerOfTen(-scale);
          rules.add(new Rule(field, scale, BigDecimal.ONE.scaleByPowerOfTen(precision - scale).subtract(quantum), quantum));
        }
      }
      return List.copyOf(rules);
    }
  };

  public static void validate(Object entity) {
    if (entity == null) return;
    for (Rule rule : RULES.get(entity.getClass())) {
      try {
        BigDecimal value = (BigDecimal) rule.field().get(entity); if (value == null) continue;
        if (value.abs().compareTo(rule.maximum()) > 0) throw new InvalidDecimalValueException();
        if (value.signum() != 0 && value.abs().compareTo(rule.quantum()) < 0) throw new ArithmeticException();
        value.setScale(rule.scale(), RoundingMode.UNNECESSARY);
      } catch (ArithmeticException ex) {
        throw new InvalidDecimalValueException();
      } catch (IllegalAccessException ex) {throw new IllegalStateException("无法读取十进制字段约束", ex);}
    }
  }
}
