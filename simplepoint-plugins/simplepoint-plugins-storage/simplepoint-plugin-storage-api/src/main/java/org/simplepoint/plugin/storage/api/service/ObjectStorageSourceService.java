/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.service;

import org.simplepoint.plugin.storage.api.model.ObjectStorageSourceContent;
import org.simplepoint.remoting.RemoteContract;

/**
 * Internal service-to-service access to source-owned objects.
 *
 * <p>The caller must supply both the persisted storage tenant and the source service name.
 * This contract is exposed only through the authenticated service router.</p>
 */
@RemoteContract(name = "common.object-storage-source")
public interface ObjectStorageSourceService {

  /**
   * Downloads one bounded object after verifying its storage tenant and source service.
   *
   * @param id object id
   * @param tenantId persisted storage tenant id
   * @param sourceServiceName expected source service name
   * @param maxBytes maximum accepted content length
   * @return downloaded content
   */
  ObjectStorageSourceContent downloadSource(
      String id,
      String tenantId,
      String sourceServiceName,
      long maxBytes
  );
}
