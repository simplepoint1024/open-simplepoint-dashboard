/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import java.util.Map;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;

/**
 * A Jackson {@link BeanPropertyWriter} decorator that applies field-level access control
 * based on the current request's {@link AuthorizationContext#getFieldPermissions()}.
 *
 * <p>The field permissions map uses keys of the form {@code "SimpleClassName#fieldName"}.
 * Access levels are defined by {@code FieldAccessType}: HIDDEN, MASKED, VISIBLE, EDITABLE.
 * HIDDEN fields are omitted from the serialized output; MASKED fields are replaced with a
 * redacted value; VISIBLE and EDITABLE fields are serialized normally.</p>
 */
public class FieldScopeBeanPropertyWriter extends BeanPropertyWriter {

  private static final String ACCESS_HIDDEN = "HIDDEN";
  private static final String ACCESS_MASKED = "MASKED";

  private final BeanPropertyWriter delegate;
  private final String fieldKey;

  FieldScopeBeanPropertyWriter(BeanPropertyWriter delegate, String simpleClassName, String fieldName) {
    super(delegate);
    this.delegate = delegate;
    this.fieldKey = simpleClassName + "#" + fieldName;
  }

  @Override
  public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
    String access = resolveAccess();
    if (ACCESS_HIDDEN.equals(access)) {
      // Omit the field entirely
      return;
    }
    if (ACCESS_MASKED.equals(access)) {
      Object rawValue = delegate.get(bean);
      gen.writeFieldName(delegate.getName());
      if (rawValue == null) {
        gen.writeNull();
      } else {
        gen.writeString(mask(rawValue.toString()));
      }
      return;
    }
    delegate.serializeAsField(bean, gen, prov);
  }

  @Override
  public void serializeAsOmittedField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
    delegate.serializeAsOmittedField(bean, gen, prov);
  }

  @Override
  public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
    String access = resolveAccess();
    if (ACCESS_HIDDEN.equals(access)) {
      gen.writeNull();
      return;
    }
    if (ACCESS_MASKED.equals(access)) {
      Object rawValue = delegate.get(bean);
      gen.writeString(rawValue == null ? null : mask(rawValue.toString()));
      return;
    }
    delegate.serializeAsElement(bean, gen, prov);
  }

  private String resolveAccess() {
    AuthorizationContext ctx = AuthorizationContextHolder.getContext();
    if (ctx == null) {
      return null;
    }
    Map<String, String> fieldPerms = ctx.getFieldPermissions();
    if (fieldPerms == null || fieldPerms.isEmpty()) {
      return null;
    }
    return fieldPerms.get(fieldKey);
  }

  /**
   * Produces a masked representation of the given string value.
   * Keeps the first 3 characters and the last 1 character; replaces the middle with "****".
   * For values shorter than 5 characters, returns "***".
   */
  static String mask(String value) {
    if (value == null) {
      return null;
    }
    int len = value.length();
    if (len <= 4) {
      return "***";
    }
    return value.substring(0, 3) + "****" + value.charAt(len - 1);
  }
}
