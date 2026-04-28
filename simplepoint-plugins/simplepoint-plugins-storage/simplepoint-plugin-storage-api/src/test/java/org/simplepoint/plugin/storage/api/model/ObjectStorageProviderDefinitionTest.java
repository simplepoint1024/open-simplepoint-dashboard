package org.simplepoint.plugin.storage.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjectStorageProviderDefinitionTest {

  @Test
  void allArgsConstructor() {
    ObjectStorageProviderDefinition def = new ObjectStorageProviderDefinition(
        "minio-1", "MinIO Local", ObjectStoragePlatformType.MINIO,
        "http://localhost:9000", "my-bucket", Boolean.TRUE);

    assertEquals("minio-1", def.getCode());
    assertEquals("MinIO Local", def.getName());
    assertEquals(ObjectStoragePlatformType.MINIO, def.getType());
    assertEquals("http://localhost:9000", def.getEndpoint());
    assertEquals("my-bucket", def.getBucket());
    assertEquals(Boolean.TRUE, def.getDefaultProvider());
  }

  @Test
  void noArgsConstructorAndSetters() {
    ObjectStorageProviderDefinition def = new ObjectStorageProviderDefinition();
    def.setCode("s3-1");
    def.setName("AWS S3");
    def.setType(ObjectStoragePlatformType.S3);
    def.setEndpoint("https://s3.amazonaws.com");
    def.setBucket("prod-bucket");
    def.setDefaultProvider(Boolean.FALSE);

    assertEquals("s3-1", def.getCode());
    assertEquals("AWS S3", def.getName());
    assertEquals(ObjectStoragePlatformType.S3, def.getType());
    assertEquals("https://s3.amazonaws.com", def.getEndpoint());
    assertEquals("prod-bucket", def.getBucket());
    assertEquals(Boolean.FALSE, def.getDefaultProvider());
  }

  @Test
  void equalityBasedOnFields() {
    ObjectStorageProviderDefinition a = new ObjectStorageProviderDefinition(
        "c1", "n1", ObjectStoragePlatformType.MINIO, "http://ep", "b1", true);
    ObjectStorageProviderDefinition b = new ObjectStorageProviderDefinition(
        "c1", "n1", ObjectStoragePlatformType.MINIO, "http://ep", "b1", true);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }
}
