/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.service;

import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;

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
}
