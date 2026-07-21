/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.constants;

/**
 * Shared object-storage endpoint paths.
 */
public final class ObjectStoragePaths {

  public static final String ADMIN_BASE = "/platform/object-storage";

  /**
   * Canonical system-wide upload and metadata API.
   */
  public static final String GLOBAL_BASE = "/object-storage";

  /**
   * Legacy service upload and metadata API.
   *
   * @deprecated use {@link #GLOBAL_BASE}
   */
  @Deprecated
  public static final String REMOTE_BASE = "/object-storage/service";

  private ObjectStoragePaths() {
  }
}
