package org.simplepoint.plugin.ai.runtime.service.node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.util.StringUtils;

/**
 * Loads the private Runtime PKI without changing JVM-global TLS state.
 */
final class RuntimeTlsContextFactory {

  private RuntimeTlsContextFactory() {
  }

  /**
   * Creates an isolated TLS 1.3 client context for Runtime dispatch.
   */
  static SSLContext create(final AiRuntimeProperties properties) {
    try {
      KeyStore keys = load(
          properties.getMtlsKeyStore(),
          properties.getMtlsKeyStorePassword(),
          "Runtime mTLS key store"
      );
      KeyStore trust = load(
          properties.getMtlsTrustStore(),
          properties.getMtlsTrustStorePassword(),
          "Runtime mTLS trust store"
      );
      KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
          KeyManagerFactory.getDefaultAlgorithm()
      );
      keyManagers.init(
          keys,
          password(
              properties.getMtlsKeyStorePassword(),
              "Runtime mTLS key store password"
          )
      );
      TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm()
      );
      trustManagers.init(trust);
      SSLContext context = SSLContext.getInstance("TLSv1.3");
      context.init(
          keyManagers.getKeyManagers(),
          trustManagers.getTrustManagers(),
          null
      );
      return context;
    } catch (GeneralSecurityException | IOException ex) {
      throw new IllegalStateException(
          "Runtime mTLS identity cannot be loaded",
          ex
      );
    }
  }

  private static KeyStore load(
      final String location,
      final String password,
      final String name
  ) throws GeneralSecurityException, IOException {
    if (!StringUtils.hasText(location)) {
      throw new IllegalStateException(name + " is not configured");
    }
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(Path.of(location.trim()))) {
      store.load(input, password(password, name + " password"));
    }
    return store;
  }

  private static char[] password(
      final String value,
      final String name
  ) {
    if (!StringUtils.hasText(value) || value.length() < 12) {
      throw new IllegalStateException(name + " is not configured safely");
    }
    return value.toCharArray();
  }
}
