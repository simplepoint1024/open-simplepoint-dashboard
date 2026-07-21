package org.simplepoint.plugin.ai.core.service.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ProviderHttpSupportTest {

  @Test
  void blocksLoopbackAndPrivateDestinationsByDefault() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.validateDestination(URI.create("http://127.0.0.1/v1"), false)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.validateDestination(URI.create("http://10.0.0.8/v1"), false)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.validateDestination(URI.create("http://[fd00::8]/v1"), false)
    );
  }

  @Test
  void allowsPrivateDestinationOnlyWhenExplicitlyEnabled() {
    assertDoesNotThrow(
        () -> ProviderHttpSupport.validateDestination(URI.create("http://127.0.0.1/v1"), true)
    );
  }

  @Test
  void blocksAdditionalReservedIpv4Ranges() throws Exception {
    assertTrue(ProviderHttpSupport.isRestrictedAddress(InetAddress.getByName("100.64.0.1")));
    assertTrue(ProviderHttpSupport.isRestrictedAddress(InetAddress.getByName("198.18.0.1")));
    assertTrue(ProviderHttpSupport.isRestrictedAddress(InetAddress.getByName("240.0.0.1")));
  }

  @Test
  void rejectsCredentialsEmbeddedInProviderUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.endpoint("https://user:password@example.com/v1", "/models")
    );
  }
}
