/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.DataScope;

/**
 * Repository interface for managing {@link DataScope} entities.
 */
public interface DataScopeRepository extends BaseRepository<DataScope, String> {

  /**
   * Finds all DataScope entities whose IDs are in the given collection.
   *
   * @param ids the collection of IDs to look up
   * @return list of matching DataScope entities
   */
  List<DataScope> findAllById(Iterable<String> ids);
}
