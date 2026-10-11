package org.simplepoint.core.base;

/** A value cannot be persisted exactly under its declared decimal column constraints. */
public final class InvalidDecimalValueException extends IllegalArgumentException {
  public InvalidDecimalValueException() {
    super("十进制数值超出允许范围或小数精度，无法无损保存");
  }
}
