package org.simplepoint.auditing.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.auditing.redis.rest.controller.RedisEntryQueryController;
import org.simplepoint.plugin.auditing.redis.service.query.RedisConnectionMetadataScanner;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryQueryService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Requires a disposable Redis; every test owns a unique prefix and deletes only its own keys. */
@EnabledIfEnvironmentVariable(named = "REDIS_QUERY_TEST_PORT", matches = "[0-9]+")
class RedisEntryQueryHttpTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<String> keys = new ArrayList<>();
  private String prefix;
  private LettuceConnectionFactory connections;
  private StringRedisTemplate redis;
  private RedisEntryQueryService queries;
  private RedisConnectionMetadataScanner scanner;
  private MockMvc mvc;
  private AuthorizationContext context;

  @BeforeEach
  void setup() {
    connections =
        new LettuceConnectionFactory(
            "127.0.0.1", Integer.parseInt(System.getenv("REDIS_QUERY_TEST_PORT")));
    connections.afterPropertiesSet();
    redis = new StringRedisTemplate(connections);
    prefix = "redis-query-test:" + UUID.randomUUID() + ":";
    for (int i = 0; i < 520; i++) {
      redis.opsForValue().set(key(String.format("item-%03d", i)), "x".repeat(i + 1));
    }
    redis.opsForHash().put(key("hash"), "field", "private-hash-value");
    redis.opsForList().rightPushAll(key("list"), "a", "b", "c");
    redis.opsForSet().add(key("set"), "a", "b");
    redis.opsForZSet().add(key("zset"), "a", 2);
    redis.opsForStream().add(key("stream"), Map.of("field", "private-stream-value"));
    redis.opsForValue().set(key("expiring"), "temporary", Duration.ofSeconds(300));
    redis.opsForValue().set(key(" spaced键 "), "secret");
    scanner = spy(new RedisConnectionMetadataScanner(connections));
    queries = new RedisEntryQueryService(scanner, new MockEnvironment());
    var proxy = new ProxyFactory(new RedisEntryQueryController(queries, schemaProvider()));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    var monitoring =
        new ProxyFactory(
            new org.simplepoint.plugin.auditing.redis.rest.controller.RedisMonitoringController(
                new org.simplepoint.plugin.auditing.redis.service.impl.RedisMonitoringServiceImpl(
                    redis,
                    new org.simplepoint.plugin.auditing.redis.service.query.RedisEntryPreviewReader(
                        redis, mapper),
                    queries)));
    monitoring.setProxyTargetClass(true);
    monitoring.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy(), monitoring.getProxy())
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    context = new AuthorizationContext();
    context.setIsAdministrator(false);
    authenticate("redis.entries.view");
  }

  private void authenticate(String authority) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "metadata-reader", "unused", List.of(new SimpleGrantedAuthority(authority))));
  }

  private String key(String suffix) {
    String key = prefix + suffix;
    keys.add(key);
    return key;
  }

  private DetailsProviderService schemaProvider() {
    var provider = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator =
        config.schemaGenerator(
            Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(provider.getDialect(JsonSchemaGenerator.class))
        .thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(provider.getDialect(JsonSchemaDetailsService.class))
        .thenReturn(mock(JsonSchemaDetailsService.class));
    return provider;
  }

  private MockHttpServletRequestBuilder request() {
    return get("/redis/entries/query")
        .param("scanPattern", prefix + "*")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private com.fasterxml.jackson.databind.JsonNode body(MockHttpServletRequestBuilder request)
      throws Exception {
    return mapper.readTree(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String ready() throws Exception {
    var initial = body(request());
    String token = initial.path("snapshotId").asText();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      var result = body(request().param("snapshotId", token));
      if (result.path("status").asText().equals("READY")) return token;
      assertThat(result.path("status").asText()).isEqualTo("BUILDING");
      assertThat(result.path("page").isNull()).isTrue();
      Thread.sleep(20);
    }
    throw new AssertionError("Redis metadata snapshot did not become ready");
  }

  @AfterEach
  void cleanup() {
    if (queries != null) queries.destroy();
    if (redis != null && !keys.isEmpty()) redis.delete(keys);
    if (connections != null) connections.destroy();
    SecurityContextHolder.clearContext();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void realRedisFullScanPaginatesBeyondFiveHundredAndReusesTheIndex() throws Exception {
    String token = ready();
    var page =
        body(
            request()
                .param("snapshotId", token)
                .param("page", "25")
                .param("size", "20")
                .param("sort", "size,desc", "key,asc"));
    assertThat(page.path("page").path("totalElements").asInt()).isEqualTo(527);
    assertThat(page.path("page").path("content").size()).isEqualTo(20);
    assertThat(page.toString())
        .doesNotContain("private-hash-value", "private-stream-value", "valuePreview");
    var filtered =
        body(
            request()
                .param("snapshotId", token)
                .param(
                    "$query",
                    """
{"version":1,"where":{"op":"or","children":[
  {"field":"size","op":"than:equal:greater","value":519},
  {"op":"not","children":[{"field":"persistent","op":"equals","value":true}]}]}}
""")
                .param("type", "equals:STRING")
                .param("size", "20")
                .param("sort", "size,desc"));
    assertThat(filtered.path("page").path("totalElements").asInt()).isEqualTo(3);
    assertThat(filtered.path("page").path("content").get(0).path("size").asInt()).isEqualTo(520);
    verify(scanner, times(1))
        .scan(
            org.mockito.ArgumentMatchers.eq(prefix + "*"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void schemaDeclaresOnlyEnforceableFieldsAndEveryFieldExecutesAgainstRedis() throws Exception {
    String token = ready();
    var schema =
        body(get("/redis/entries/query/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
            .path("schema");
    List<String> fields =
        List.of("key", "type", "ttlSeconds", "persistent", "size", "editable", "observedAt");
    assertThat(schema.path("x-query").path("fields").size()).isEqualTo(fields.size());
    assertThat(schema.path("x-query").path("maxPageSize").asInt()).isEqualTo(100);
    for (String field : fields) {
      assertThat(schema.path("properties").has(field)).isTrue();
      assertThat(schema.path("x-query").path("fields").path(field).path("operators").isArray())
          .isTrue();
      var page =
          body(
              request()
                  .param("snapshotId", token)
                  .param("sort", field + ",asc")
                  .param(
                      "$query",
                      "{\"version\":1,\"where\":{\"field\":\""
                          + field
                          + "\",\"op\":\"is:not:null\"}}"));
      assertThat(page.path("page").path("totalElements").asInt())
          .isEqualTo(field.equals("ttlSeconds") ? 1 : 527);
    }
  }

  @Test
  void metadataIsAtomicPerKeyAndDoesNotReadValuesOrLoseWhitespaceIdentities() throws Exception {
    String token = ready();
    for (var expected :
        Map.of("hash", "HASH", "list", "LIST", "set", "SET", "zset", "ZSET", "stream", "STREAM")
            .entrySet()) {
      var page =
          body(request().param("snapshotId", token).param("type", "equals:" + expected.getValue()));
      assertThat(page.path("page").path("totalElements").asInt()).isEqualTo(1);
      assertThat(page.path("page").path("content").get(0).path("size").asInt()).isPositive();
    }
    String document =
        mapper.writeValueAsString(
            Map.of(
                "version",
                1,
                "where",
                Map.of("field", "key", "op", "equals", "value", prefix + " spaced键 ")));
    var exact = body(request().param("snapshotId", token).param("$query", document));
    assertThat(exact.path("page").path("totalElements").asInt()).isEqualTo(1);
    assertThat(exact.path("page").path("content").get(0).path("key").asText())
        .endsWith(" spaced键 ");
    var ttl = body(request().param("snapshotId", token).param("persistent", "equals:false"));
    assertThat(ttl.path("page").path("content").get(0).path("ttlSeconds").asLong())
        .isBetween(290L, 300L);
  }

  @Test
  void invalidQueriesReturnSafe400AndSnapshotReuseStillEnforcesFieldPolicies() throws Exception {
    String token = ready();
    for (var request :
        List.of(
            request().param("valuePreview", "like:secret"),
            request().param("sort", "valuePreview,asc"),
            request().param("size", "101"),
            request().param("type", "equals:invalid"))) {
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    context = new AuthorizationContext();
    context.setFieldPermissions(
        Map.of("RedisEntrySummary#size", "MASKED", "RedisEntrySummary#type", "HIDDEN"));
    var masked = body(request().param("snapshotId", token)).path("page").path("content").get(0);
    assertThat(masked.path("size").asText()).isEqualTo("***");
    assertThat(masked.has("type")).isFalse();
    assertThat(masked.has("editable")).isFalse();
    mvc.perform(request().param("sort", "size,desc")).andExpect(status().isBadRequest());
    mvc.perform(request().param("type", "equals:STRING")).andExpect(status().isBadRequest());
    var schema =
        body(get("/redis/entries/query/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
            .path("schema");
    assertThat(schema.path("x-query").path("fields").has("size")).isFalse();
    assertThat(schema.path("properties").has("type")).isFalse();
    context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("RedisEntrySummary#key", "HIDDEN"));
    assertThatThrownBy(() -> body(request())).hasRootCauseInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                body(
                    get("/redis/entries/query/schema")
                        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)))
        .hasRootCauseInstanceOf(AccessDeniedException.class);
  }

  @Test
  void queryAndSchemaUseReadPermissionNotMutationPermission() throws Exception {
    authenticate("redis.entries.edit");
    assertThatThrownBy(() -> body(request())).hasRootCauseInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> body(get("/redis/entries/query/schema")))
        .hasRootCauseInstanceOf(AccessDeniedException.class);
    authenticate("ROLE_Administrator");
    assertThat(body(get("/redis/entries/query/schema")).path("schema").path("x-query").isObject())
        .isTrue();
  }

  @Test
  void obsoleteSynchronousListEndpointIsNotExposed() throws Exception {
    mvc.perform(get("/redis/entries")).andExpect(status().isMethodNotAllowed());
  }

  @Test
  void buttonsFollowPermissionsAndActualSchemaMatchesTheBrowserFixture() throws Exception {
    context.setResources(Set.of("redis.entries.view"));
    var readOnly =
        body(
            get("/redis/entries/query/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(readOnly.path("buttons").size()).isEqualTo(1);
    assertThat(readOnly.path("buttons").get(0).path("key").asText()).isEqualTo("view");
    context = new AuthorizationContext();
    context.setIsAdministrator(true);
    var schema =
        body(
            get("/redis/entries/query/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(schema.path("buttons").size()).isEqualTo(4);
    var file = java.nio.file.Path.of("../../simplepoint-react/scripts/redis-schema.json");
    if (Boolean.getBoolean("simplepoint.exportRedisSchema")) {
      java.nio.file.Files.writeString(file, schema.toPrettyString());
    } else assertThat(mapper.readTree(java.nio.file.Files.readString(file))).isEqualTo(schema);
  }

  @Test
  void detailKeepsTheExactWhitespaceKeyInsteadOfReadingItsTrimmedNeighbour() throws Exception {
    redis.opsForValue().set(key(" spaced键"), "neighbour-value");
    var detail =
        body(
            get("/redis/entries/detail")
                .param("key", prefix + " spaced键 ")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(detail.path("key").asText()).isEqualTo(prefix + " spaced键 ");
    assertThat(detail.path("value").asText()).isEqualTo("secret");
  }

  @Test
  void deletePreservesCommaAndWhitespaceKeysAndInvalidatesTheObservedVersion() throws Exception {
    String left = key("left"), right = key("right");
    String combined = key("left," + right);
    for (String target : List.of(left, right, combined))
      redis.opsForValue().set(target, "owned-test-value");
    String first = ready();
    authenticate("redis.entries.delete");
    mvc.perform(delete("/redis/entries").param("keys", combined)).andExpect(status().isOk());
    assertThat(redis.hasKey(combined)).isFalse();
    assertThat(redis.hasKey(left)).isTrue();
    assertThat(redis.hasKey(right)).isTrue();
    mvc.perform(delete("/redis/entries").param("keys", prefix + " spaced键 "))
        .andExpect(status().isOk());
    assertThat(redis.hasKey(prefix + " spaced键 ")).isFalse();
    authenticate("redis.entries.view");
    assertThat(body(request().param("snapshotId", first)).path("status").asText())
        .isEqualTo("EXPIRED");
  }

  @Test
  void largeStringAndBinaryPreviewsAreBoundedAndNotEditable() throws Exception {
    String large = key("large");
    redis.opsForValue().set(large, "x".repeat(600000));
    var preview = body(get("/redis/entries/detail").param("key", large));
    assertThat(preview.path("value").asText()).hasSize(262144);
    assertThat(preview.path("size").asLong()).isEqualTo(600000);
    assertThat(preview.path("truncated").asBoolean()).isTrue();
    assertThat(preview.path("editable").asBoolean()).isFalse();
    assertThat(redis.opsForValue().size(large)).isEqualTo(600000);
    String binary = key("binary");
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Boolean>)
            connection ->
                connection
                    .stringCommands()
                    .set(
                        binary.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        new byte[] {0, (byte) 255, 1}));
    var encoded = body(get("/redis/entries/detail").param("key", binary));
    assertThat(encoded.path("valueEncoding").asText()).isEqualTo("base64");
    assertThat(encoded.path("value").asText()).isEqualTo("AP8B");
    assertThat(encoded.path("editable").asBoolean()).isFalse();
  }

  @Test
  void collectionPreviewsRetainStructureButBoundItemsAndReturnedBytes() throws Exception {
    String hash = key("large-hash"),
        set = key("large-set"),
        list = key("large-list"),
        sorted = key("large-zset");
    for (int i = 0; i < 240; i++) {
      String value = i + ":" + "x".repeat(12000);
      redis.opsForHash().put(hash, "field-" + i, value);
      redis.opsForSet().add(set, value);
      redis.opsForList().rightPush(list, value);
      redis.opsForZSet().add(sorted, value, i);
    }
    for (String target : List.of(hash, set, list, sorted)) {
      var preview = body(get("/redis/entries/detail").param("key", target));
      assertThat(preview.path("size").asLong()).isEqualTo(240);
      assertThat(preview.path("editable").asBoolean()).isFalse();
      assertThat(preview.path("sampled").asBoolean()).isTrue();
      assertThat(preview.path("truncated").asBoolean()).isTrue();
      String value = preview.path("value").asText();
      assertThat(value.length()).isLessThan(300000);
      var payload = mapper.readTree(value);
      assertThat(payload.path("values").size()).isBetween(1, 100);
      assertThat(payload.has("size")).isFalse();
    }
    var stream = body(get("/redis/entries/detail").param("key", prefix + "stream"));
    assertThat(stream.path("value").asText()).contains("private-stream-value");
  }

  @Test
  void detailProjectionHonorsLegacyHiddenMaskedAndReadonlyFields() throws Exception {
    context.setFieldPermissions(
        Map.of("RedisEntrySummary#valuePreview", "MASKED", "RedisEntryDetail#size", "HIDDEN"));
    var masked =
        body(
            get("/redis/entries/detail")
                .param("key", prefix + "hash")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(masked.path("value").asText()).isEqualTo("***");
    assertThat(masked.path("editable").asText()).isEqualTo("***");
    assertThat(masked.has("size")).isFalse();
    context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("RedisEntryDetail#value", "HIDDEN"));
    var hidden =
        body(
            get("/redis/entries/detail")
                .param("key", prefix + "hash")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(hidden.has("value")).isFalse();
    assertThat(hidden.has("valueEncoding")).isFalse();
    authenticate("redis.entries.edit");
    assertThatThrownBy(
            () ->
                mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                            "/redis/entries")
                        .contentType("application/json")
                        .content(
                            mapper.writeValueAsString(
                                Map.of(
                                    "key",
                                    prefix + "item-000",
                                    "value",
                                    "changed",
                                    "persistent",
                                    true)))
                        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)))
        .hasRootCauseInstanceOf(AccessDeniedException.class);
    assertThat(redis.opsForValue().get(prefix + "item-000")).isEqualTo("x");
  }

  @Test
  void readonlyFieldsRemoveWriteButtonsWithoutHidingUnrelatedMetadata() throws Exception {
    context.setResources(
        Set.of(
            "redis.entries.view",
            "redis.entries.create",
            "redis.entries.edit",
            "redis.entries.delete"));
    context.setFieldPermissions(Map.of("RedisEntryDetail#value", "VISIBLE"));
    var schema =
        body(
            get("/redis/entries/query/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(schema.path("buttons").findValuesAsText("key")).containsExactly("view", "delete");
    assertThat(schema.path("schema").path("properties").has("persistent")).isTrue();
    var detail =
        body(
            get("/redis/entries/detail")
                .param("key", prefix + "item-000")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
    assertThat(detail.path("value").asText()).isEqualTo("x");
    assertThat(detail.path("editable").asBoolean()).isFalse();
  }
}
