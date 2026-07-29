package org.simplepoint.mcp.gateway.client;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;

/**
 * Lazily builds the dedicated Gateway-to-Runtime TLS 1.3 client identity.
 */
final class RuntimeMcpTlsClient {

  private final McpGatewayProperties properties;

  private volatile SSLContext sslContext;

  RuntimeMcpTlsClient(final McpGatewayProperties properties) {
    this.properties = properties;
  }

  void customize(final HttpClient.Builder builder) {
    if (!properties.isRuntimeMtlsEnabled()) {
      throw new IllegalStateException(
          "Managed Runtime MCP connections require Gateway mTLS"
      );
    }
    SSLParameters parameters = new SSLParameters();
    parameters.setProtocols(new String[]{"TLSv1.3"});
    builder.sslContext(context());
    builder.sslParameters(parameters);
  }

  private SSLContext context() {
    SSLContext current = sslContext;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      current = sslContext;
      if (current == null) {
        current = load();
        sslContext = current;
      }
      return current;
    }
  }

  private SSLContext load() {
    try {
      char[] keyPassword = required(
          properties.getRuntimeMtlsKeyStorePassword(),
          "Runtime mTLS key-store password"
      ).toCharArray();
      char[] trustPassword = required(
          properties.getRuntimeMtlsTrustStorePassword(),
          "Runtime mTLS trust-store password"
      ).toCharArray();
      KeyStore keys = loadStore(
          required(
              properties.getRuntimeMtlsKeyStore(),
              "Runtime mTLS key store"
          ),
          keyPassword
      );
      assertIdentity(keys, required(
          properties.getRuntimeMtlsIdentity(),
          "Runtime mTLS identity"
      ));
      KeyStore trust = loadStore(
          required(
              properties.getRuntimeMtlsTrustStore(),
              "Runtime mTLS trust store"
          ),
          trustPassword
      );
      KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
          KeyManagerFactory.getDefaultAlgorithm()
      );
      keyManagers.init(keys, keyPassword);
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
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Gateway Runtime mTLS configuration is invalid",
          ex
      );
    }
  }

  private static KeyStore loadStore(
      final String location,
      final char[] password
  ) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(Path.of(location))) {
      store.load(input, password);
    }
    return store;
  }

  private static void assertIdentity(
      final KeyStore store,
      final String identity
  ) throws Exception {
    for (String alias : java.util.Collections.list(store.aliases())) {
      if (!(store.getCertificate(alias) instanceof X509Certificate certificate)) {
        continue;
      }
      Collection<List<?>> names = certificate.getSubjectAlternativeNames();
      if (names == null) {
        continue;
      }
      boolean found = names.stream().anyMatch(name ->
          name.size() >= 2
              && Integer.valueOf(6).equals(name.getFirst())
              && identity.equals(name.get(1))
      );
      if (found) {
        return;
      }
    }
    throw new IllegalStateException(
        "Gateway Runtime certificate does not contain identity " + identity
    );
  }

  private static String required(final String value, final String name) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalStateException(name + " must not be blank");
    }
    return normalized;
  }
}
