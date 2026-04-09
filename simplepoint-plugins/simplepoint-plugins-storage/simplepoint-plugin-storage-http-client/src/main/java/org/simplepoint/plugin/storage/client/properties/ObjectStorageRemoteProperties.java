/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Remote object-storage client properties.
 */
@Data
@ConfigurationProperties(prefix = ObjectStorageRemoteProperties.PREFIX)
public class ObjectStorageRemoteProperties {

  public static final String PREFIX = "simplepoint.storage.remote";

  private String serviceName;

  private String scheme = "http";

  public String baseUrl() {
    return scheme + "://" + serviceName;
  }
}
