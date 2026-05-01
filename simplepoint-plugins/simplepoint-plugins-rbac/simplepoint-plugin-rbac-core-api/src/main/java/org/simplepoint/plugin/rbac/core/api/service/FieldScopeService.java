/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.FieldScopeEntry;

/**
 * Service interface for managing {@link FieldScope} entities.
 */
public interface FieldScopeService extends BaseService<FieldScope, String> {

  /**
   * Replaces all entries in the given field scope with the provided list.
   *
   * @param fieldScopeId the ID of the FieldScope to update
   * @param entries      the new list of entries
   * @return the updated FieldScope
   */
  FieldScope replaceEntries(String fieldScopeId, Collection<FieldScopeEntry> entries);
}
