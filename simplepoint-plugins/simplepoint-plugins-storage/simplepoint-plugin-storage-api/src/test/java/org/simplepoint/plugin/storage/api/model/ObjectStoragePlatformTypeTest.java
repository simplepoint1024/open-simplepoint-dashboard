package org.simplepoint.plugin.storage.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ObjectStoragePlatformTypeTest {

  @Test
  void enumValueCount() {
    assertEquals(6, ObjectStoragePlatformType.values().length);
  }

  @Test
  void valueOfReturnsCorrectEnum() {
    assertEquals(ObjectStoragePlatformType.MINIO, ObjectStoragePlatformType.valueOf("MINIO"));
    assertEquals(ObjectStoragePlatformType.S3, ObjectStoragePlatformType.valueOf("S3"));
    assertEquals(ObjectStoragePlatformType.ALIYUN_OSS, ObjectStoragePlatformType.valueOf("ALIYUN_OSS"));
    assertEquals(ObjectStoragePlatformType.TENCENT_COS, ObjectStoragePlatformType.valueOf("TENCENT_COS"));
    assertEquals(ObjectStoragePlatformType.QINIU_KODO, ObjectStoragePlatformType.valueOf("QINIU_KODO"));
    assertEquals(ObjectStoragePlatformType.CEPH, ObjectStoragePlatformType.valueOf("CEPH"));
  }

  @Test
  void ordinals() {
    assertEquals(0, ObjectStoragePlatformType.MINIO.ordinal());
    assertEquals(1, ObjectStoragePlatformType.S3.ordinal());
    assertEquals(2, ObjectStoragePlatformType.ALIYUN_OSS.ordinal());
    assertEquals(3, ObjectStoragePlatformType.TENCENT_COS.ordinal());
    assertEquals(4, ObjectStoragePlatformType.QINIU_KODO.ordinal());
    assertEquals(5, ObjectStoragePlatformType.CEPH.ordinal());
  }
}
