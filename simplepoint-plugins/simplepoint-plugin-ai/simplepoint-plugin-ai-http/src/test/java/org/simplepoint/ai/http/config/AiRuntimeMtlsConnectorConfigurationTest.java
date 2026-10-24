package org.simplepoint.ai.http.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

/** Validates connector assembly without loading keys or opening sockets. */
class AiRuntimeMtlsConnectorConfigurationTest {
  @Test
  void connectorCannotReuseTheActualHostPort() {
    var properties = properties();
    properties.setMtlsControlPort(8001);
    var customizer = new AiRuntimeMtlsConnectorConfiguration().aiRuntimeMtlsConnector(properties);
    var factory = new TomcatServletWebServerFactory(8001);
    assertThatThrownBy(() -> customizer.customize(factory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ports must differ");
    assertThat(factory.getAdditionalConnectors()).isEmpty();
  }

  @Test
  void formerAiPortIsAvailableWhenTheSelectedHostUsesAnotherPort() {
    var properties = properties();
    properties.setMtlsControlPort(2888);
    var factory = new TomcatServletWebServerFactory(8001);
    new AiRuntimeMtlsConnectorConfiguration().aiRuntimeMtlsConnector(properties).customize(factory);
    assertThat(factory.getAdditionalConnectors()).hasSize(1);
    var connector = factory.getAdditionalConnectors().get(0);
    assertThat(connector.getPort()).isEqualTo(2888);
    assertThat(connector.getSecure()).isTrue();
    assertThat(connector.findSslHostConfigs()[0].getCertificateVerification().toString())
        .isEqualTo("REQUIRED");
  }

  private AiRuntimeProperties properties() {
    var properties = new AiRuntimeProperties();
    properties.setMtlsKeyStore("fixture-keystore.p12");
    properties.setMtlsKeyStorePassword("fixture-only");
    properties.setMtlsTrustStore("fixture-truststore.p12");
    properties.setMtlsTrustStorePassword("fixture-only");
    return properties;
  }
}
