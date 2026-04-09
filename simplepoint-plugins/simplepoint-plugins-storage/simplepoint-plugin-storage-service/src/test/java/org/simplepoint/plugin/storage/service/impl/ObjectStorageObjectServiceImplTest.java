package org.simplepoint.plugin.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageTenantQuotaRepository;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
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

  @Test
  void uploadShouldRejectWhenQuotaExceeded() {
    ObjectStorageProperties properties = configuredProperties();
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("default");
    quota.setEnabled(true);
    quota.setQuotaBytes(10L);
    when(quotaRepository.findActiveByTenantId("default")).thenReturn(Optional.of(quota));
    when(repository.sumActiveContentLengthByTenantId("default")).thenReturn(9L);

    ObjectStorageObjectServiceImpl service = new ObjectStorageObjectServiceImpl(
        repository,
        detailsProviderService,
        quotaRepository,
        List.of(driver),
        properties,
        "common"
    );

    MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "ab".getBytes());

    assertThrows(IllegalStateException.class, () -> service.upload(file, null));
    verify(driver, never()).write(any(), any());
  }

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
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ObjectStorageObjectServiceImpl service = new ObjectStorageObjectServiceImpl(
        repository,
        detailsProviderService,
        quotaRepository,
        List.of(driver),
        properties,
        "common"
    );

    MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "hello".getBytes());
    ObjectStorageObject object = service.upload(file, null);

    ArgumentCaptor<ObjectStorageWriteRequest> writeRequestCaptor = ArgumentCaptor.forClass(ObjectStorageWriteRequest.class);
    verify(driver).write(any(), writeRequestCaptor.capture());
    assertEquals("demo-bucket", writeRequestCaptor.getValue().getBucket());
    assertEquals("demo.txt", object.getOriginalFileName());
    assertEquals("demo-bucket", object.getBucket());
    assertEquals("common", object.getSourceServiceName());
    assertEquals(ObjectStoragePlatformType.MINIO, object.getProviderType());
  }

  private static ObjectStorageProperties configuredProperties() {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    properties.setDefaultProvider("minio");
    ObjectStorageProperties.ProviderProperties provider = new ObjectStorageProperties.ProviderProperties();
    provider.setEnabled(true);
    provider.setType(ObjectStoragePlatformType.MINIO);
    provider.setBucket("demo-bucket");
    provider.setEndpoint("http://127.0.0.1:9000");
    provider.setAccessKey("minio");
    provider.setSecretKey("minio123");
    properties.setProviders(Map.of("minio", provider));
    return properties;
  }
}
