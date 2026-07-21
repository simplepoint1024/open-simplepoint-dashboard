package org.simplepoint.plugin.storage.api.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;

class ObjectStoragePropertiesTest {

  @Test
  void prefixConstant() {
    assertEquals("simplepoint.storage", ObjectStorageProperties.PREFIX);
  }

  @Test
  void defaultConstructorInitializesProviders() {
    ObjectStorageProperties props = new ObjectStorageProperties();
    assertNotNull(props.getProviders());
    assertTrue(props.getProviders().isEmpty());
  }

  @Test
  void minioBootstrapDefaultsAreSafeForNonComposeRuntime() {
    ObjectStorageProperties props = new ObjectStorageProperties();

    assertFalse(props.getBootstrap().getMinio().isEnabled());
    assertEquals("minio", props.getBootstrap().getMinio().getCode());
    assertEquals("http://minio:9000", props.getBootstrap().getMinio().getEndpoint());
    assertEquals("simplepoint", props.getBootstrap().getMinio().getBucket());
    assertTrue(props.getBootstrap().getMinio().isDefaultProvider());
  }

  @Test
  void defaultProviderSetterGetter() {
    ObjectStorageProperties props = new ObjectStorageProperties();
    props.setDefaultProvider("minio-1");
    assertEquals("minio-1", props.getDefaultProvider());
  }

  @Test
  void providerPropertiesDefaults() {
    ObjectStorageProperties.ProviderProperties pp = new ObjectStorageProperties.ProviderProperties();
    assertEquals("us-east-1", pp.getRegion());
    assertEquals(Boolean.TRUE, pp.getEnabled());
  }

  @Test
  void providerPropertiesSettersGetters() {
    ObjectStorageProperties.ProviderProperties pp = new ObjectStorageProperties.ProviderProperties();
    pp.setName("MinIO");
    pp.setType(ObjectStoragePlatformType.MINIO);
    pp.setEndpoint("http://localhost:9000");
    pp.setRegion("ap-east-1");
    pp.setAccessKey("accessKey");
    pp.setSecretKey("secretKey");
    pp.setBucket("test-bucket");
    pp.setBasePath("/uploads");
    pp.setPathStyleAccess(Boolean.TRUE);
    pp.setEnabled(Boolean.FALSE);
    pp.setPublicBaseUrl("https://cdn.example.com");

    assertEquals("MinIO", pp.getName());
    assertEquals(ObjectStoragePlatformType.MINIO, pp.getType());
    assertEquals("http://localhost:9000", pp.getEndpoint());
    assertEquals("ap-east-1", pp.getRegion());
    assertEquals("accessKey", pp.getAccessKey());
    assertEquals("secretKey", pp.getSecretKey());
    assertEquals("test-bucket", pp.getBucket());
    assertEquals("/uploads", pp.getBasePath());
    assertEquals(Boolean.TRUE, pp.getPathStyleAccess());
    assertEquals(Boolean.FALSE, pp.getEnabled());
    assertEquals("https://cdn.example.com", pp.getPublicBaseUrl());
  }

  @Test
  void providersMapCanBePopulated() {
    ObjectStorageProperties props = new ObjectStorageProperties();
    ObjectStorageProperties.ProviderProperties pp = new ObjectStorageProperties.ProviderProperties();
    pp.setName("test");
    props.getProviders().put("test-provider", pp);
    assertEquals(pp, props.getProviders().get("test-provider"));
  }
}
