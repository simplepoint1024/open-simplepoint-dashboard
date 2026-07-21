/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.simplepoint.plugin.storage.api.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.model.ObjectStorageConnectionTestResult;
import org.simplepoint.plugin.storage.api.model.ObjectStorageProviderDefinition;
import org.simplepoint.plugin.storage.api.model.ResolvedObjectStorageProvider;

/**
 * Manages and resolves system-global OSS provider configurations.
 */
public interface ObjectStorageProviderConfigService
    extends BaseService<ObjectStorageProviderConfig, String> {

  /**
   * Lists enabled provider summaries for upload clients.
   */
  Collection<ObjectStorageProviderDefinition> providers();

  /**
   * Resolves a provider into decrypted runtime properties.
   */
  ResolvedObjectStorageProvider resolve(String requestedCode, boolean allowDisabled);

  /**
   * Tests the persisted provider credentials and bucket.
   */
  ObjectStorageConnectionTestResult testConnection(String id);
}
