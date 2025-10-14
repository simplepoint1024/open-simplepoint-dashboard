/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.menu.service.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.F;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.locale.I18nContextHolder;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Implementation of {@link MenuService} providing business logic for menu management.
 *
 * <p>This service handles CRUD operations for menus by interacting with {@link MenuRepository}.
 * It extends {@link BaseServiceImpl} to inherit standard data operations.</p>
 *
 * @author Your Name
 * @since 1.0
 */
@Slf4j
@AmqpRemoteService
public class MenuServiceImpl
    extends BaseServiceImpl<MenuRepository, Menu, String>
    implements MenuService {

  /**
   * Constructs a MenusServiceImpl with the specified repository and optional metadata sync service.
   *
   * @param repository the repository used for menu operations
   */
  public MenuServiceImpl(
      final MenuRepository repository
  ) {
    super(repository);
  }

  @Override
  public <S extends Menu> Page<S> limit(Map<String, String> attributes, Pageable pageable)
      throws Exception {
    return F.processing(
        super.limit(attributes, pageable),
        s -> I18nContextHolder.localize(
            s.getContent(),
            Menu::getLabel,
            Menu::setLabel,
            attributes.containsKey(I18nContextHolder.DISABLE_I18N)
        ));
  }

  @Override
  public void sync(Set<MenuChildren> data) {
    Set<Menu> menus = new HashSet<>();
    Queue<MenuChildren> queue = new LinkedBlockingQueue<>(data);
    while (!queue.isEmpty()) {
      MenuChildren current = queue.poll();
      if (current.getUuid() == null) {
        current.setUuid(UUID.randomUUID().toString());
      }
      if (current.getChildren() != null) {
        queue.addAll(current.getChildren().stream()
            .peek(child -> child.setParent(current.getUuid())).toList());
      }
      Menu menu = new Menu();
      BeanUtils.copyProperties(current, menu);
      menus.add(menu);
    }
    for (Menu menu : menus) {
      Menu example = new Menu();
      example.setPath(menu.getPath());
      if (!exists(example)) {
        try {
          this.add(menu);
        } catch (Exception e) {
          log.warn("Failed to add menu: {}", menu.getPath());
        }
      }
    }
  }
}

