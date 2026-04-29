/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Optional;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.core.api.repository.UserPreferenceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UserPreferenceService;
import org.simplepoint.security.entity.UserPreference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of UserPreferenceService that stores per-user preferences in the database.
 * All operations are scoped to the currently authenticated user obtained from the authorization context.
 */
@Service
public class UserPreferenceServiceImpl implements UserPreferenceService {

  private final UserPreferenceRepository repository;

  /**
   * Constructs a UserPreferenceServiceImpl with the given repository.
   *
   * @param repository the UserPreferenceRepository
   */
  public UserPreferenceServiceImpl(final UserPreferenceRepository repository) {
    this.repository = repository;
  }

  private String currentUserId() {
    return AuthorizationContextHolder.getContext().getUserId();
  }

  @Override
  public Optional<String> getPreference(String preferenceKey) {
    return repository
        .findByUserIdAndPreferenceKey(currentUserId(), preferenceKey)
        .map(UserPreference::getPreferenceValue);
  }

  @Override
  @Transactional
  public void setPreference(String preferenceKey, String preferenceValue) {
    String userId = currentUserId();
    UserPreference pref = repository
        .findByUserIdAndPreferenceKey(userId, preferenceKey)
        .orElseGet(UserPreference::new);
    pref.setUserId(userId);
    pref.setPreferenceKey(preferenceKey);
    pref.setPreferenceValue(preferenceValue);
    repository.save(pref);
  }

  @Override
  @Transactional
  public void deletePreference(String preferenceKey) {
    repository.deleteByUserIdAndPreferenceKey(currentUserId(), preferenceKey);
  }
}
