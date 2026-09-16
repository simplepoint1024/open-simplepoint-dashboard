package org.simplepoint.plugin.storage.service.initialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageProviderConfigRepository;
import org.simplepoint.plugin.storage.api.service.ObjectStorageProviderConfigService;

class ObjectStorageMinioInitializerTest {

  @Test
  void contribution_disabled_returnsNull() {
    ObjectStorageProperties properties = properties(false);
    ObjectStorageMinioInitializer initializer = new ObjectStorageMinioInitializer();

    BootstrapContribution contribution = initializer.objectStorageMinioBootstrapContribution(
        properties,
        mock(ObjectStorageProviderConfigRepository.class),
        mock(ObjectStorageProviderConfigService.class)
    ).contribution();

    assertThat(contribution).isNull();
  }

  @Test
  void contribution_enabled_hasStableMetadata() {
    ObjectStorageProperties properties = properties(true);
    ObjectStorageMinioInitializer initializer = new ObjectStorageMinioInitializer();

    BootstrapContribution contribution = initializer.objectStorageMinioBootstrapContribution(
        properties,
        mock(ObjectStorageProviderConfigRepository.class),
        mock(ObjectStorageProviderConfigService.class)
    ).contribution();

    assertThat(contribution.moduleCode()).isEqualTo("storage");
    assertThat(contribution.contributionType()).isEqualTo("object-storage-provider");
    assertThat(contribution.contributionKey()).isEqualTo("docker-compose-minio-provider");
    assertThat(contribution.version()).isEqualTo("1");
    assertThat(contribution.order()).isEqualTo(350);
  }

  @Test
  void bootstrap_noExistingProvider_createsDefaultMinioProvider() throws Exception {
    ObjectStorageProperties properties = properties(true);
    ObjectStorageProviderConfigRepository repository = mock(ObjectStorageProviderConfigRepository.class);
    ObjectStorageProviderConfigService providerService = mock(ObjectStorageProviderConfigService.class);
    when(repository.findActiveByCode("minio")).thenReturn(Optional.empty());
    when(repository.findDefaultEnabled()).thenReturn(Optional.empty());
    when(providerService.create(any(ObjectStorageProviderConfig.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ObjectStorageMinioInitializer initializer = new ObjectStorageMinioInitializer();
    initializer.objectStorageMinioBootstrapContribution(properties, repository, providerService)
        .contribution()
        .action()
        .run();

    ArgumentCaptor<ObjectStorageProviderConfig> captor =
        ArgumentCaptor.forClass(ObjectStorageProviderConfig.class);
    verify(providerService).create(captor.capture());
    ObjectStorageProviderConfig provider = captor.getValue();
    assertThat(provider.getCode()).isEqualTo("minio");
    assertThat(provider.getType()).isEqualTo(ObjectStoragePlatformType.MINIO);
    assertThat(provider.getEndpoint()).isEqualTo("http://minio:9000");
    assertThat(provider.getAccessKey()).isEqualTo("simplepoint");
    assertThat(provider.getSecretKey()).isEqualTo("simplepoint123");
    assertThat(provider.getBucket()).isEqualTo("simplepoint");
    assertThat(provider.getPathStyleAccess()).isTrue();
    assertThat(provider.getDefaultProvider()).isTrue();
  }

  @Test
  void bootstrap_existingProvider_doesNotOverwriteAdministratorChanges() throws Exception {
    ObjectStorageProperties properties = properties(true);
    ObjectStorageProviderConfigRepository repository = mock(ObjectStorageProviderConfigRepository.class);
    ObjectStorageProviderConfigService providerService = mock(ObjectStorageProviderConfigService.class);
    ObjectStorageProviderConfig existing = new ObjectStorageProviderConfig();
    existing.setCode("minio");
    when(repository.findActiveByCode("minio")).thenReturn(Optional.of(existing));

    ObjectStorageMinioInitializer initializer = new ObjectStorageMinioInitializer();
    initializer.objectStorageMinioBootstrapContribution(properties, repository, providerService)
        .contribution()
        .action()
        .run();

    verify(providerService, never()).create(any(ObjectStorageProviderConfig.class));
  }

  @Test
  void bootstrap_existingDefault_createsMinioWithoutReplacingIt() throws Exception {
    ObjectStorageProperties properties = properties(true);
    ObjectStorageProviderConfigRepository repository = mock(ObjectStorageProviderConfigRepository.class);
    ObjectStorageProviderConfigService providerService = mock(ObjectStorageProviderConfigService.class);
    when(repository.findActiveByCode("minio")).thenReturn(Optional.empty());
    when(repository.findDefaultEnabled()).thenReturn(Optional.of(new ObjectStorageProviderConfig()));
    when(providerService.create(any(ObjectStorageProviderConfig.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ObjectStorageMinioInitializer initializer = new ObjectStorageMinioInitializer();
    initializer.objectStorageMinioBootstrapContribution(properties, repository, providerService)
        .contribution()
        .action()
        .run();

    ArgumentCaptor<ObjectStorageProviderConfig> captor =
        ArgumentCaptor.forClass(ObjectStorageProviderConfig.class);
    verify(providerService).create(captor.capture());
    assertThat(captor.getValue().getDefaultProvider()).isFalse();
  }

  private ObjectStorageProperties properties(final boolean enabled) {
    ObjectStorageProperties properties = new ObjectStorageProperties();
    ObjectStorageProperties.MinioBootstrapProperties minio = properties.getBootstrap().getMinio();
    minio.setEnabled(enabled);
    minio.setAccessKey("simplepoint");
    minio.setSecretKey("simplepoint123");
    return properties;
  }
}
