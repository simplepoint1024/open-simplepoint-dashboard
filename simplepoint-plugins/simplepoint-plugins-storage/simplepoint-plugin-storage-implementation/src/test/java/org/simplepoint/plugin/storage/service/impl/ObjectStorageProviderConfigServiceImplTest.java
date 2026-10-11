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

  @Test
  void readOnlyCredentialCannotBeEncryptedAndSavedThroughTheCustomCreatePath() {
    var context = new org.simplepoint.core.AuthorizationContext();
    context.setFieldPermissions(Map.of(ObjectStorageProviderConfig.class.getName() + "#secretKey", "VISIBLE"));
    org.simplepoint.core.RequestContextHolder.setContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    try {
      var input = provider(); input.setSecretKey("replacement");
      assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.create(input));
      org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
      assertEquals("secret-value", cipher.decrypt(input.getSecretKeyCiphertext()));
    } finally {
      org.simplepoint.core.RequestContextHolder.clearContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  @Test
  void connectionTestCannotUseCredentialsOutsideThePageScope() {
    var context = new org.simplepoint.core.AuthorizationContext(); context.setUserId("reader");
    context.setResourceDataScopes(Map.of("storage.providers.view", new org.simplepoint.core.ResourceDataScope("SELF", java.util.Set.of(), false)));
    org.simplepoint.core.RequestContextHolder.setContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    org.simplepoint.core.ResourcePolicyContext.set("storage.providers.view");
    try {
      var input = provider(); input.setCreatedBy("other");
      when(repository.findActiveById("provider-1")).thenReturn(Optional.of(input));
      assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.testConnection("provider-1"));
      org.mockito.Mockito.verifyNoInteractions(driver);
    } finally {
      org.simplepoint.core.RequestContextHolder.clearContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      org.simplepoint.core.ResourcePolicyContext.clear();
    }
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

  @Test
  void partialEditRestoresProtectedConfigurationBeforeValidationAndRetainsTheSecret() {
    var context = new org.simplepoint.core.AuthorizationContext();
    context.setUserId("editor");
    var permissions = new java.util.LinkedHashMap<String, String>();
    List.of("name", "type", "endpoint", "region", "accessKey", "bucket", "enabled", "defaultProvider")
        .forEach(field -> permissions.put(ObjectStorageProviderConfig.class.getName() + "#" + field, "MASKED"));
    context.setFieldPermissions(permissions);
    org.simplepoint.core.RequestContextHolder.setContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    try {
      var current = provider();
      var patch = new ObjectStorageProviderConfig(); patch.setId(current.getId()); patch.setDescription("edited note");
      when(repository.findActiveById(current.getId())).thenReturn(Optional.of(current));
      when(repository.findActiveByCode(current.getCode())).thenReturn(Optional.of(current));
      when(repository.findById(current.getId())).thenReturn(Optional.of(current));
      when(repository.getDomainClass()).thenReturn(ObjectStorageProviderConfig.class);
      when(repository.updateById(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      var schema = mapper.createObjectNode(); var fields = schema.putObject("properties");
      for (var field : ObjectStorageProviderConfig.class.getDeclaredFields()) fields.putObject(field.getName());
      var generator = org.mockito.Mockito.mock(org.simplepoint.api.security.generator.JsonSchemaGenerator.class);
      when(detailsProviderService.getDialect(org.simplepoint.api.security.service.JsonSchemaDetailsService.class))
          .thenReturn(org.mockito.Mockito.mock(org.simplepoint.api.security.service.JsonSchemaDetailsService.class));
      when(detailsProviderService.getDialect(org.simplepoint.api.security.generator.JsonSchemaGenerator.class)).thenReturn(generator);
      when(generator.generateSchema(ObjectStorageProviderConfig.class)).thenReturn(schema);
      var result = service.modifyById(patch);
      assertEquals("edited note", result.getDescription());
      assertEquals(current.getAccessKey(), result.getAccessKey());
      assertEquals(current.getBucket(), result.getBucket());
      assertEquals(current.getName(), result.getName());
      assertEquals("secret-value", cipher.decrypt(result.getSecretKeyCiphertext()));
    } finally {
      org.simplepoint.core.RequestContextHolder.clearContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }
}
