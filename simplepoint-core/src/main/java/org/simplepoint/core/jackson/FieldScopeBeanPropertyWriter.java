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
import org.simplepoint.core.AuthorizationContext;

/**
 * A Jackson {@link BeanPropertyWriter} decorator that applies field-level access control
 * based on the current request's {@link AuthorizationContext#getFieldPermissions()}.
 *
 * <p>The field permissions map uses keys of the form {@code "ResourceClassName#fieldName"}.
 * Access levels are defined by {@code FieldAccessType}: HIDDEN, MASKED, VISIBLE, EDITABLE.
 * HIDDEN fields are omitted from the serialized output; MASKED fields are replaced with a
 * redacted value; VISIBLE and EDITABLE fields are serialized normally.</p>
 */
public class FieldScopeBeanPropertyWriter extends BeanPropertyWriter {

  private static final String ACCESS_MASKED = "MASKED";

  private final BeanPropertyWriter delegate;
  private final Class<?> resourceType;
  private final String fieldName;

  FieldScopeBeanPropertyWriter(BeanPropertyWriter delegate, Class<?> resourceType, String fieldName) {
    super(delegate);
    this.delegate = delegate;
    this.resourceType = resourceType;
    this.fieldName = fieldName;
  }

  @Override
  public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
    String access = resolveAccess();
    if (FieldPermissionPolicy.hidden(access)) {
      // Omit the field entirely
      return;
    }
    if (ACCESS_MASKED.equals(access)) {
      Object rawValue = delegate.get(bean);
      gen.writeFieldName(delegate.getName());
      if (rawValue == null) {
        gen.writeNull();
      } else {
        gen.writeString("***");
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
    if (FieldPermissionPolicy.hidden(access)) {
      gen.writeNull();
      return;
    }
    if (ACCESS_MASKED.equals(access)) {
      Object rawValue = delegate.get(bean);
      if (rawValue == null) gen.writeNull();
      else gen.writeString("***");
      return;
    }
    delegate.serializeAsElement(bean, gen, prov);
  }

  private String resolveAccess() {
    return FieldPermissionPolicy.access(resourceType, fieldName);
  }
}
