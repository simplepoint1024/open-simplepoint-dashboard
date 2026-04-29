package org.simplepoint.plugin.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.model.ObjectStorageProviderDefinition;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageTenantQuotaRepository;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageReadResult;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteRequest;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageWriteResult;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ObjectStorageObjectServiceImplTest {

  @Mock
  private ObjectStorageObjectRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private ObjectStorageTenantQuotaRepository quotaRepository;

  @Mock
  private ObjectStorageDriver driver;

  // ── providers() ──────────────────────────────────────────────────────────

  @Test
  void providersShouldReturnEmptyWhenNoProvidersConfigured() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    Collection<ObjectStorageProviderDefinition> result = service.providers();

    assertTrue(result.isEmpty());
  }

  @Test
  void providersShouldReturnEmptyWhenProvidersMapIsEmpty() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    properties.setProviders(Map.of());
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    assertTrue(service.providers().isEmpty());
  }

  @Test
  void providersShouldReturnOnlyEnabledProviders() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    ObjectStorageProperties.ProviderProperties enabled = providerProps(ObjectStoragePlatformType.MINIO, true, "b1");
    ObjectStorageProperties.ProviderProperties disabled = providerProps(ObjectStoragePlatformType.MINIO, false, "b2");
    properties.setProviders(Map.of("minio", enabled, "disabled-provider", disabled));
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    Collection<ObjectStorageProviderDefinition> result = service.providers();

    assertEquals(1, result.size());
    assertEquals("minio", result.iterator().next().getCode());
  }

  @Test
  void providersShouldMarkDefaultProvider() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    properties.setDefaultProvider("minio");
    ObjectStorageProperties.ProviderProperties p1 = providerProps(ObjectStoragePlatformType.MINIO, true, "b1");
    ObjectStorageProperties.ProviderProperties p2 = providerProps(ObjectStoragePlatformType.MINIO, true, "b2");
    properties.setProviders(Map.of("minio", p1, "other", p2));
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    List<ObjectStorageProviderDefinition> result = List.copyOf(service.providers());

    ObjectStorageProviderDefinition defaultDef = result.stream()
        .filter(d -> "minio".equals(d.getCode()))
        .findFirst().orElseThrow();
    assertTrue(defaultDef.getDefaultProvider());
  }

  @Test
  void providersShouldUseCodeAsNameWhenNameIsBlank() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    ObjectStorageProperties.ProviderProperties p = providerProps(ObjectStoragePlatformType.MINIO, true, "bkt");
    p.setName("  ");
    properties.setProviders(Map.of("minio-code", p));
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    ObjectStorageProviderDefinition def = service.providers().iterator().next();
    assertEquals("minio-code", def.getName());
  }

  // ── findActiveById ───────────────────────────────────────────────────────

  @Test
  void findActiveByIdShouldDelegateToRepository() {
    ObjectStorageObject obj = new ObjectStorageObject();
    obj.setId("obj-1");
    when(repository.findActiveById("obj-1")).thenReturn(Optional.of(obj));
    ObjectStorageObjectServiceImpl service = buildService(configuredProperties(), "app");

    Optional<ObjectStorageObject> result = service.findActiveById("obj-1");

    assertTrue(result.isPresent());
    assertEquals("obj-1", result.get().getId());
  }

  @Test
  void findActiveByIdShouldReturnEmptyWhenNotFound() {
    when(repository.findActiveById("missing")).thenReturn(Optional.empty());
    ObjectStorageObjectServiceImpl service = buildService(configuredProperties(), "app");

    assertTrue(service.findActiveById("missing").isEmpty());
  }

  // ── download ─────────────────────────────────────────────────────────────

  @Test
  void downloadShouldThrowWhenObjectNotFound() {
    when(repository.findActiveById("absent")).thenReturn(Optional.empty());
    ObjectStorageObjectServiceImpl service = buildService(configuredProperties(), "app");

    assertThrows(NoSuchElementException.class, () -> service.download("absent"));
  }

  @Test
  void downloadShouldReturnReadResultFromDriver() {
    ObjectStorageObject obj = new ObjectStorageObject();
    obj.setId("obj-dl");
    obj.setProviderCode("minio");
    obj.setBucket("demo-bucket");
    obj.setObjectKey("tenant/demo.txt");
    obj.setOriginalFileName("demo.txt");

    when(repository.findActiveById("obj-dl")).thenReturn(Optional.of(obj));
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    InputStream stream = new ByteArrayInputStream("hello".getBytes());
    ObjectStorageReadResult readResult = new ObjectStorageReadResult(stream, "text/plain", 5L, "demo.txt");
    when(driver.read(any(), eq("demo-bucket"), eq("tenant/demo.txt"), eq("demo.txt")))
        .thenReturn(readResult);

    ObjectStorageObjectServiceImpl service = buildService(configuredProperties(), "app");
    ObjectStorageReadResult result = service.download("obj-dl");

    assertNotNull(result);
    assertEquals("text/plain", result.getContentType());
  }

  // ── upload – null / empty file ────────────────────────────────────────────

  @Test
  void uploadShouldRejectNullFile() {
    ObjectStorageObjectServiceImpl service = buildService(configuredProperties(), "app");

    assertThrows(IllegalArgumentException.class, () -> service.upload(null, null));
  }

  @Test
  void uploadShouldRejectEmptyFile() {
    ObjectStorageObjectServiceImpl service = buildService(configuredProperties(), "app");
    MockMultipartFile empty = new MockMultipartFile("file", new byte[0]);

    assertThrows(IllegalArgumentException.class, () -> service.upload(empty, null));
  }

  // ── upload – provider resolution ──────────────────────────────────────────

  @Test
  void uploadShouldThrowWhenNoProvidersConfigured() {
    ObjectStorageObjectServiceImpl service = buildService(new ObjectStorageProperties(), "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "x".getBytes());

    assertThrows(IllegalStateException.class, () -> service.upload(file, null));
  }

  @Test
  void uploadShouldThrowWhenProviderNotFound() {
    ObjectStorageProperties properties = configuredProperties();
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    ObjectStorageUploadRequest request = new ObjectStorageUploadRequest();
    request.setProviderCode("nonexistent");

    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "x".getBytes());

    assertThrows(NoSuchElementException.class, () -> service.upload(file, request));
  }

  @Test
  void uploadShouldThrowWhenProviderIsDisabled() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    ObjectStorageProperties.ProviderProperties disabled = providerProps(ObjectStoragePlatformType.MINIO, false, "bkt");
    properties.setDefaultProvider("minio");
    properties.setProviders(Map.of("minio", disabled));
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "x".getBytes());

    assertThrows(IllegalStateException.class, () -> service.upload(file, null));
  }

  @Test
  void uploadShouldThrowWhenNoBucketConfigured() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    ObjectStorageProperties.ProviderProperties p = new ObjectStorageProperties.ProviderProperties();
    p.setEnabled(true);
    p.setType(ObjectStoragePlatformType.MINIO);
    p.setBucket("  "); // blank bucket
    properties.setDefaultProvider("minio");
    properties.setProviders(Map.of("minio", p));
    ObjectStorageObjectServiceImpl service = buildService(properties, "app");

    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "x".getBytes());

    assertThrows(IllegalStateException.class, () -> service.upload(file, null));
  }

  // ── upload – quota enforcement ────────────────────────────────────────────

  @Test
  void uploadShouldRejectWhenQuotaExceeded() {
    ObjectStorageProperties properties = configuredProperties();
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("default");
    quota.setEnabled(true);
    quota.setQuotaBytes(10L);
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.of(quota));
    when(repository.sumActiveContentLengthByTenantId("default")).thenReturn(9L);

    ObjectStorageObjectServiceImpl service = buildService(properties, "common");

    MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "ab".getBytes());

    assertThrows(IllegalStateException.class, () -> service.upload(file, null));
    verify(driver, never()).write(any(), any());
  }

  @Test
  void uploadShouldAllowWhenWithinQuota() {
    ObjectStorageProperties properties = configuredProperties();
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("default");
    quota.setEnabled(true);
    quota.setQuotaBytes(1000L);
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.of(quota));
    when(repository.sumActiveContentLengthByTenantId("default")).thenReturn(5L);
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "default/2025/01/01/uuid-demo.txt", "etag", "http://url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "hello".getBytes());

    ObjectStorageObject result = service.upload(file, null);
    assertNotNull(result);
  }

  @Test
  void uploadShouldAllowWhenQuotaIsDisabled() {
    ObjectStorageProperties properties = configuredProperties();
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("default");
    quota.setEnabled(false);
    quota.setQuotaBytes(1L); // very small but disabled
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.of(quota));
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "default/file.txt", "etag", "http://url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "data".getBytes());

    assertNotNull(service.upload(file, null));
  }

  // ── upload – object key conflict ──────────────────────────────────────────

  @Test
  void uploadShouldThrowWhenObjectKeyAlreadyExists() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());

    ObjectStorageUploadRequest request = new ObjectStorageUploadRequest();
    request.setObjectKey("existing/key.txt");

    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(eq("minio"), eq("demo-bucket"), any()))
        .thenReturn(Optional.of(new ObjectStorageObject()));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "data".getBytes());

    assertThrows(IllegalArgumentException.class, () -> service.upload(file, request));
  }

  // ── upload – metadata persistence ────────────────────────────────────────

  @Test
  void uploadShouldUseDefaultProviderAndPersistMetadata() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket",
        "tenant/demo.txt",
        "etag-1",
        "http://127.0.0.1/demo-bucket/tenant/demo.txt"
    ));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectStorageObjectServiceImpl service = buildService(properties, "common");

    MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "hello".getBytes());
    ObjectStorageObject object = service.upload(file, null);

    ArgumentCaptor<ObjectStorageWriteRequest> writeRequestCaptor =
        ArgumentCaptor.forClass(ObjectStorageWriteRequest.class);
    verify(driver).write(any(), writeRequestCaptor.capture());
    assertEquals("demo-bucket", writeRequestCaptor.getValue().getBucket());
    assertEquals("demo.txt", object.getOriginalFileName());
    assertEquals("demo-bucket", object.getBucket());
    assertEquals("common", object.getSourceServiceName());
    assertEquals(ObjectStoragePlatformType.MINIO, object.getProviderType());
  }

  @Test
  void uploadShouldUseCustomFileNameFromRequest() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "default/custom-name.pdf", "etag", "http://url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageUploadRequest request = new ObjectStorageUploadRequest();
    request.setFileName("custom-name.pdf");
    request.setSourceServiceName("custom-service");

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "original.txt", "application/pdf", "data".getBytes());

    ObjectStorageObject result = service.upload(file, request);

    assertEquals("custom-name.pdf", result.getOriginalFileName());
    assertEquals("custom-service", result.getSourceServiceName());
  }

  @Test
  void uploadShouldNullifyContentTypeWhenBlank() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "key", "etag", null));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    // null content type should default to application/octet-stream
    MockMultipartFile file = new MockMultipartFile("file", "f.bin", null, "raw".getBytes());

    ObjectStorageObject result = service.upload(file, null);
    assertEquals("application/octet-stream", result.getContentType());
  }

  @Test
  void uploadShouldSetETagAndAccessUrlFromWriteResult() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "key.txt", "my-etag", "http://access-url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "data".getBytes());

    ObjectStorageObject result = service.upload(file, null);
    assertEquals("my-etag", result.getETag());
    assertEquals("http://access-url", result.getAccessUrl());
  }

  @Test
  void uploadShouldDeleteObjectWhenSaveFails() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "key.txt", "etag", "http://url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenThrow(new RuntimeException("DB error"));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "data".getBytes());

    assertThrows(RuntimeException.class, () -> service.upload(file, null));
    verify(driver).delete(any(), eq("demo-bucket"), eq("key.txt"));
  }

  @Test
  void uploadShouldUseDirectoryFromRequest() {
    ObjectStorageProperties properties = configuredProperties();
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "demo-bucket", "uploads/default/2025/01/01/uuid-f.txt", "etag", "http://url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageUploadRequest request = new ObjectStorageUploadRequest();
    request.setDirectory("uploads");

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "data".getBytes());

    ObjectStorageObject result = service.upload(file, request);
    assertNotNull(result);
  }

  @Test
  void uploadShouldAutoSelectSingleEnabledProviderWhenDefaultNotSet() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    // no defaultProvider set
    ObjectStorageProperties.ProviderProperties p = providerProps(ObjectStoragePlatformType.MINIO, true, "bucket-auto");
    properties.setProviders(Map.of("auto-minio", p));

    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.empty());
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);
    when(driver.write(any(), any())).thenReturn(new ObjectStorageWriteResult(
        "bucket-auto", "key.txt", "etag", "http://url"));
    when(repository.findActiveByProviderCodeAndBucketAndObjectKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ObjectStorageObjectServiceImpl service = buildService(properties, "app");
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", "data".getBytes());

    ObjectStorageObject result = service.upload(file, null);
    assertEquals("auto-minio", result.getProviderCode());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private ObjectStorageObjectServiceImpl buildService(
      final ObjectStorageProperties properties,
      final String applicationName
  ) {
    return new ObjectStorageObjectServiceImpl(
        repository,
        detailsProviderService,
        quotaRepository,
        List.of(driver),
        properties,
        applicationName
    );
  }

  static ObjectStorageProperties configuredProperties() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    properties.setDefaultProvider("minio");
    ObjectStorageProperties.ProviderProperties provider = providerProps(ObjectStoragePlatformType.MINIO, true, "demo-bucket");
    provider.setEndpoint("http://127.0.0.1:9000");
    provider.setAccessKey("minio");
    provider.setSecretKey("minio123");
    properties.setProviders(Map.of("minio", provider));
    return properties;
  }

  private static ObjectStorageProperties.ProviderProperties providerProps(
      final ObjectStoragePlatformType type,
      final boolean enabled,
      final String bucket
  ) {
    ObjectStorageProperties.ProviderProperties p = new ObjectStorageProperties.ProviderProperties();
    p.setEnabled(enabled);
    p.setType(type);
    p.setBucket(bucket);
    return p;
  }
}
