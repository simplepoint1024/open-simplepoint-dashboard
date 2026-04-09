/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.service;

import java.util.Collection;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStorageProviderDefinition;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service contract for object-storage metadata and file operations.
 */
public interface ObjectStorageObjectService extends BaseService<ObjectStorageObject, String> {

  /**
   * Returns selectable provider definitions.
   *
   * @return provider definitions
   */
  Collection<ObjectStorageProviderDefinition> providers();

  /**
   * Uploads a file and records metadata.
   *
   * @param file    multipart file
   * @param request upload request
   * @return stored object metadata
   */
  ObjectStorageObject upload(MultipartFile file, ObjectStorageUploadRequest request);

  /**
   * Finds active metadata by id.
   *
   * @param id object id
   * @return object metadata
   */
  Optional<ObjectStorageObject> findActiveById(String id);

  /**
   * Downloads stored object content.
   *
   * @param id object id
   * @return content descriptor
   */
  ObjectStorageReadResult download(String id);

  /**
   * Removes objects from remote storage and metadata tables.
   *
   * @param ids object ids
   */
  void removeByIds(Collection<String> ids);
}
