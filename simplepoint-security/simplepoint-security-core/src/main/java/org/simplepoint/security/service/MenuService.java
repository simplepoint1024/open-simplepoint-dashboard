/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.TreeMenu;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for managing {@link Menu} entities.
 *
 * <p>Provides abstraction for menu-related operations and extends the base service
 * with CRUD functionalities.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@AmqpRemoteClient(to = "security.menu")
public interface MenuService extends BaseService<Menu, String> {
  /**
   * Synchronizes the menu data with the provided set of {@link MenuChildren}.
   *
   * @param data the set of menu children to synchronize
   */
  void sync(Set<MenuChildren> data);

  /**
   * Retrieves the collection of menus accessible to the current user.
   *
   * @return a collection of {@link TreeMenu} entities available to the user
   */
  Collection<TreeMenu> routes();

  /**
   * Retrieves a paginated list of tree-structured menus based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   the pagination information
   * @return a paginated list of {@link TreeMenu} entities matching the filters
   */
  Page<TreeMenu> limitTree(Map<String, String> attributes, Pageable pageable);
}
