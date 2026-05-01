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
 */
public class FieldScopeBeanSerializerModifier extends BeanSerializerModifier {

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                    BeanDescription beanDesc,
                                                    List<BeanPropertyWriter> beanProperties) {
    String simpleClassName = beanDesc.getBeanClass().getSimpleName();
    List<BeanPropertyWriter> wrapped = new ArrayList<>(beanProperties.size());
    for (BeanPropertyWriter writer : beanProperties) {
      wrapped.add(new FieldScopeBeanPropertyWriter(writer, simpleClassName, writer.getName()));
    }
    return wrapped;
  }
}
