package org.simplepoint.plugin.storage.client.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.storage.client.service.ObjectStorageRemoteService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObjectStorageEndpointTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ObjectStorageHttpClientAutoConfiguration.class,
          ObjectStorageDirectClientAutoConfiguration.class));

  @Test
  void directEndpointPreservesTheOwnerPrefixWithoutServiceDiscovery() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/common/object-storage/objects/object-one", exchange -> {
      byte[] body = "{\"id\":\"object-one\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try {
      runner.withPropertyValues("simplepoint.storage.remote.base-url=http://127.0.0.1:"
          + server.getAddress().getPort() + "/common").run(context -> {
        assertThat(context).hasNotFailed().hasSingleBean(ObjectStorageRemoteService.class);
        assertThat(context).doesNotHaveBean("objectStorageRestClientBuilder");
        assertThat(context.getBean(ObjectStorageRemoteService.class).metadata("object-one"))
            .get().extracting(object -> object.getId()).isEqualTo("object-one");
      });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void unconfiguredClientsStayAbsentAndConflictingOrCredentialBearingEndpointsFail() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ObjectStorageRemoteService.class));
    runner.withPropertyValues("simplepoint.storage.remote.service-name=common")
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ObjectStorageRemoteService.class));
    runner.withPropertyValues("simplepoint.storage.remote.service-name=common",
        "simplepoint.storage.remote.base-url=http://localhost/common")
        .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("simplepoint.storage.remote.base-url=http://user:secret@localhost/common")
        .run(context -> assertThat(context).hasFailed());
  }
}
