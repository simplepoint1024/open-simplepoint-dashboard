package org.simplepoint.ai.server.config;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Adds a dedicated client-certificate port for Runtime node control traffic.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = AiRuntimeProperties.PREFIX,
    name = "mtls-enabled",
    havingValue = "true"
)
public class AiRuntimeMtlsConnectorConfiguration {

  /**
   * Keeps the ordinary AI port on HTTP while isolating node mTLS on 2889.
   */
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory>
      aiRuntimeMtlsConnector(final AiRuntimeProperties properties) {
    validate(properties);
    return factory -> factory.addAdditionalConnectors(
        connector(properties)
    );
  }

  private Connector connector(final AiRuntimeProperties properties) {
    Connector connector = new Connector(
        TomcatServletWebServerFactory.DEFAULT_PROTOCOL
    );
    connector.setPort(properties.getMtlsControlPort());
    connector.setScheme("https");
    connector.setSecure(true);
    connector.setProperty("SSLEnabled", "true");

    SSLHostConfig host = new SSLHostConfig();
    host.setProtocols("+TLSv1.3");
    host.setCertificateVerification("required");
    host.setTruststoreFile(properties.getMtlsTrustStore().trim());
    host.setTruststorePassword(properties.getMtlsTrustStorePassword());
    host.setTruststoreType("PKCS12");

    SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
        host,
        SSLHostConfigCertificate.Type.UNDEFINED
    );
    certificate.setCertificateKeystoreFile(
        properties.getMtlsKeyStore().trim()
    );
    certificate.setCertificateKeystorePassword(
        properties.getMtlsKeyStorePassword()
    );
    certificate.setCertificateKeystoreType("PKCS12");
    host.addCertificate(certificate);
    connector.addSslHostConfig(host);
    return connector;
  }

  private void validate(final AiRuntimeProperties properties) {
    Integer port = properties.getMtlsControlPort();
    if (port == null || port <= 0 || port > 65_535 || port == 2888) {
      throw new IllegalStateException("Runtime mTLS control port is invalid");
    }
    require(properties.getMtlsKeyStore(), "Runtime mTLS key store");
    require(
        properties.getMtlsKeyStorePassword(),
        "Runtime mTLS key store password"
    );
    require(properties.getMtlsTrustStore(), "Runtime mTLS trust store");
    require(
        properties.getMtlsTrustStorePassword(),
        "Runtime mTLS trust store password"
    );
    String identityPrefix = properties.getMtlsNodeIdentityPrefix();
    if (!StringUtils.hasText(identityPrefix)
        || !identityPrefix.startsWith("spiffe://")
        || !identityPrefix.endsWith("/")) {
      throw new IllegalStateException(
          "Runtime mTLS node identity prefix is invalid"
      );
    }
  }

  private void require(final String value, final String name) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException(name + " is required");
    }
  }
}
