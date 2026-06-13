package org.simplepoint.plugin.storage.api.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ObjectStoragePathsTest {

  @Test
  void adminBasePath() {
    assertEquals("/platform/object-storage", ObjectStoragePaths.ADMIN_BASE);
  }

  @Test
  void remoteBasePath() {
    assertEquals("/object-storage/service", ObjectStoragePaths.REMOTE_BASE);
  }

  @Test
  void pathsAreNotNull() {
    assertNotNull(ObjectStoragePaths.ADMIN_BASE);
    assertNotNull(ObjectStoragePaths.REMOTE_BASE);
  }
}
