/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A Jackson {@link BeanSerializerModifier} that wraps every {@link BeanPropertyWriter}
 * with a {@link FieldScopeBeanPropertyWriter}, enabling per-request field-level access control.
 *
 * <p>Application types and extension models using platform permission contracts are wrapped;
 * unrelated framework and library types remain untouched.</p>
 */
public class FieldScopeBeanSerializerModifier extends BeanSerializerModifier {

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                    BeanDescription beanDesc,
                                                    List<BeanPropertyWriter> beanProperties) {
    // Only apply field-scope enforcement to application-owned types
    if (!FieldPermissionPolicy.supports(beanDesc.getBeanClass())) {
      return beanProperties;
    }
    List<BeanPropertyWriter> wrapped = new ArrayList<>(beanProperties.size());
    for (BeanPropertyWriter writer : beanProperties) {
      String logicalName = beanDesc.findProperties().stream()
          .filter(property -> property.getName().equals(writer.getName()))
          .map(com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition::getInternalName)
          .findFirst().orElse(writer.getName());
      wrapped.add(new FieldScopeBeanPropertyWriter(writer, beanDesc.getBeanClass(), logicalName));
    }
    return wrapped;
  }
}
