/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Optional;

/**
 * Service for managing per-user UI preferences.
 * All operations are scoped to the currently authenticated user.
 */
public interface UserPreferenceService {

  /**
   * Retrieves the preference value for the current user.
   *
   * @param preferenceKey the preference key
   * @return an Optional containing the preference value if it exists
   */
  Optional<String> getPreference(String preferenceKey);

  /**
   * Saves (upserts) a preference value for the current user.
   *
   * @param preferenceKey   the preference key
   * @param preferenceValue the preference value (typically JSON)
   */
  void setPreference(String preferenceKey, String preferenceValue);

  /**
   * Deletes a preference for the current user.
   *
   * @param preferenceKey the preference key
   */
  void deletePreference(String preferenceKey);
}
