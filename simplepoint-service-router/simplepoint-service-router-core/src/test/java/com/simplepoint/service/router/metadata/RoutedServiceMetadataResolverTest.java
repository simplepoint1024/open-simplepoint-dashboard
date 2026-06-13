package com.simplepoint.service.router.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;

class RoutedServiceMetadataResolverTest {

  @Test
  void resolvesRoutedServiceMetadata() {
    Optional<RoutedServiceMetadata> metadata = RoutedServiceMetadataResolver.resolve(SampleService.class);

    assertThat(metadata).isPresent();
    assertThat(metadata.get().name()).isEqualTo("sample.SampleService");
    assertThat(metadata.get().version()).isEqualTo("2.0");
    assertThat(metadata.get().timeout()).isEqualTo(1500L);
    assertThat(metadata.get().retries()).isEqualTo(2);
    assertThat(metadata.get().methods())
        .extracting(RoutedMethodMetadata::methodId)
        .containsExactly("find", "ping");
  }

  @Test
  void resolvesRemoteContractMetadata() {
    Optional<RoutedServiceMetadata> metadata = RoutedServiceMetadataResolver.resolve(LegacyRemoteService.class);

    assertThat(metadata).isPresent();
    assertThat(metadata.get().name()).isEqualTo("legacy.sample");
    assertThat(metadata.get().version()).isEqualTo("1");
    assertThat(metadata.get().timeout()).isEqualTo(3000L);
    assertThat(metadata.get().retries()).isZero();
    assertThat(metadata.get().methods())
        .extracting(RoutedMethodMetadata::methodId)
        .containsExactly("ping");
  }

  @RoutedService(name = "sample.SampleService", version = "2.0", timeout = 1500L, retries = 2)
  interface SampleService {

    @RoutedMethod("find")
    String findById(String id);

    String ping();
  }

  @RemoteContract(name = "legacy.sample")
  interface LegacyRemoteService {

    String ping();
  }
}
