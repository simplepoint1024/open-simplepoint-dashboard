/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.jackson;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * A Jackson {@link com.fasterxml.jackson.databind.Module} that enables field-level access control
 * during JSON serialization.
 *
 * <p>Registers {@link FieldScopeBeanSerializerModifier} so that every serialized bean property
 * is checked against the current user's {@code fieldPermissions} in
 * {@link org.simplepoint.core.AuthorizationContext}.  Fields configured as HIDDEN are omitted;
 * fields configured as MASKED are replaced with a redacted value.</p>
 */
public class FieldScopeJacksonModule extends SimpleModule {

  public FieldScopeJacksonModule() {
    super("FieldScopeJacksonModule");
    setSerializerModifier(new FieldScopeBeanSerializerModifier());
  }
}
