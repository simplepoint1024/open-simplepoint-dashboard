package org.simplepoint.remoting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteContractResolverTest {

  @Test
  void resolve_returnsContractMetadata() {
    RemoteContractMetadata metadata = RemoteContractResolver.resolve(SampleContract.class)
        .orElseThrow();

    assertEquals(SampleContract.class.getName(), metadata.interfaceName());
    assertEquals("sample.contract", metadata.name());
    assertEquals("2", metadata.version());
  }

  @Test
  void resolve_ignoresUnannotatedInterface() {
    assertTrue(RemoteContractResolver.resolve(UnannotatedContract.class).isEmpty());
  }

  @RemoteContract(name = "sample.contract", version = "2")
  interface SampleContract {
  }

  interface UnannotatedContract {
  }
}
