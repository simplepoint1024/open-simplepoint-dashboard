package org.simplepoint.plugin.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.model.ResolvedObjectStorageProvider;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageProviderConfigRepository;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.service.security.ObjectStorageCredentialCipher;

@ExtendWith(MockitoExtension.class)
class ObjectStorageProviderConfigServiceImplTest {

  @Mock
  private ObjectStorageProviderConfigRepository repository;

  @Mock
  private ObjectStorageObjectRepository objectRepository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private ObjectStorageDriver driver;

  private ObjectStorageProperties properties;

  private ObjectStorageCredentialCipher cipher;

  private ObjectStorageProviderConfigServiceImpl service;

  @BeforeEach
  void setUp() {
    properties = new ObjectStorageProperties();
    properties.setCredentialEncryptionKey("provider-config-test-key");
    cipher = new ObjectStorageCredentialCipher(properties);
    service = new ObjectStorageProviderConfigServiceImpl(
        repository,
        detailsProviderService,
        objectRepository,
        cipher,
        properties,
        List.of(driver)
    );
  }

  @Test
  void providersFallBackToLegacyPropertiesWhenDatabaseIsEmpty() {
    ObjectStorageProperties.ProviderProperties minio = new ObjectStorageProperties.ProviderProperties();
    minio.setName("MinIO");
    minio.setType(ObjectStoragePlatformType.MINIO);
    minio.setBucket("files");
    minio.setEnabled(true);
    properties.setDefaultProvider("minio");
    properties.setProviders(Map.of("minio", minio));
    when(repository.existsAnyActive()).thenReturn(false);

    var providers = service.providers();

    assertEquals(1, providers.size());
    assertTrue(providers.iterator().next().getDefaultProvider());
  }

  @Test
  void resolveUsesDatabaseDefaultAndDecryptsSecret() {
    ObjectStorageProviderConfig provider = provider();
    when(repository.existsAnyActive()).thenReturn(true);
    when(repository.findDefaultEnabled()).thenReturn(Optional.of(provider));

    ResolvedObjectStorageProvider resolved = service.resolve(null, false);

    assertEquals("primary", resolved.code());
    assertEquals("secret-value", resolved.properties().getSecretKey());
  }

  @Test
  void testConnectionUsesMatchingDriver() {
    ObjectStorageProviderConfig provider = provider();
    when(repository.findActiveById("provider-1")).thenReturn(Optional.of(provider));
    when(driver.supports(ObjectStoragePlatformType.MINIO)).thenReturn(true);

    var result = service.testConnection("provider-1");

    assertTrue(result.success());
    verify(driver).testConnection(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void referencedProviderCannotBeDeleted() {
    ObjectStorageProviderConfig provider = provider();
    when(repository.findActiveById("provider-1")).thenReturn(Optional.of(provider));
    when(objectRepository.existsActiveByProviderCode("primary")).thenReturn(true);

    assertThrows(IllegalStateException.class, () -> service.removeByIds(List.of("provider-1")));
  }

  private ObjectStorageProviderConfig provider() {
    ObjectStorageProviderConfig provider = new ObjectStorageProviderConfig();
    provider.setId("provider-1");
    provider.setCode("primary");
    provider.setName("Primary MinIO");
    provider.setType(ObjectStoragePlatformType.MINIO);
    provider.setEndpoint("http://minio:9000");
    provider.setRegion("us-east-1");
    provider.setAccessKey("access-key");
    provider.setSecretKeyCiphertext(cipher.encrypt("secret-value"));
    provider.setBucket("files");
    provider.setEnabled(true);
    provider.setDefaultProvider(true);
    return provider;
  }
}
