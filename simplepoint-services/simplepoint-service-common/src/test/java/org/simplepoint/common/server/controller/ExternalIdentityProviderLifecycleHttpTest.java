package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.jackson.FieldScopeJackson3Module;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.constants.ExternalIdentityProviderPaths;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.properties.ExternalIdentityProviderProperties;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityProviderRepository;
import org.simplepoint.plugin.oidc.rest.controller.ExternalIdentityProviderController;
import org.simplepoint.plugin.oidc.service.security.ExternalIdentityProviderCredentialCipher;
import org.simplepoint.plugin.oidc.service.service.impl.ExternalIdentityProviderServiceImpl;
import org.simplepoint.plugin.oidc.service.support.ExternalIdentityClientRegistrationFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual controller, field-policy JSON, encrypted credentials and SQL; no live provider credentials. */
class ExternalIdentityProviderLifecycleHttpTest {
  private static final String PATH = ExternalIdentityProviderPaths.BASE;
  private static final String RESOURCE = ExternalIdentityProvider.class.getName() + "#";
  // Deliberately synthetic values used only by this isolated database and local metadata server.
  private static final String ORIGINAL_SECRET = "test-only-original-secret";
  private final ObjectMapper mapper = new ObjectMapper();
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private AuthorizationContext context;
  private ExternalIdentityProviderServiceImpl service;
  private ExternalIdentityProviderCredentialCipher cipher;
  private MockEnvironment environment;
  private String id;
  private String originalCiphertext;
  private HttpServer metadataServer;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:external-idp-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(ExternalIdentityProvider.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    var properties = new ExternalIdentityProviderProperties();
    properties.setCredentialEncryptionKey("isolated-http-regression-test-only-key");
    cipher = new ExternalIdentityProviderCredentialCipher(properties);
    var persisted = configuration();
    originalCiphertext = cipher.encrypt(ORIGINAL_SECRET);
    persisted.setClientSecretCiphertext(originalCiphertext);
    session.persist(persisted); session.flush(); id = persisted.getId(); session.clear();
    context = new AuthorizationContext(); context.setUserId("test-editor");
    context.setIsAdministrator(false); context.setDataScopeType("NONE");
    context.setResources(List.of("identityProviders.view", "identityProviders.edit", "identityProviders.create", "identityProviders.test"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var repository = mock(ExternalIdentityProviderRepository.class,
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl<>(ExternalIdentityProvider.class, session))));
    doAnswer(call -> session.createQuery("from ExternalIdentityProvider where id = :id and deletedAt is null", ExternalIdentityProvider.class)
        .setParameter("id", call.getArgument(0)).uniqueResultOptional()).when(repository).findActiveById(anyString());
    doAnswer(call -> session.createQuery("from ExternalIdentityProvider where registrationId = :registration and deletedAt is null", ExternalIdentityProvider.class)
        .setParameter("registration", call.getArgument(0)).uniqueResultOptional()).when(repository).findActiveByRegistrationId(anyString());
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    environment = new MockEnvironment();
    service = new ExternalIdentityProviderServiceImpl(repository, details, mock(ExternalIdentityLinkRepository.class),
        cipher, new ExternalIdentityClientRegistrationFactory(), environment);
    mvc = MockMvcBuilders.standaloneSetup(new ExternalIdentityProviderController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(tools.jackson.databind.json.JsonMapper.builder()
            .addModule(new FieldScopeJackson3Module()).build())).build();
    factory.getStatistics().clear();
  }

  private ExternalIdentityProvider configuration() {
    var provider = new ExternalIdentityProvider();
    provider.setRegistrationId("isolated-provider"); provider.setDisplayName("Isolated provider");
    provider.setPreset(ExternalIdentityProviderPreset.CUSTOM); provider.setProtocol(ExternalIdentityProviderProtocol.OAUTH2);
    provider.setClientId("test-only-client-id"); provider.setClientAuthenticationMethod("client_secret_basic");
    provider.setAuthorizationUri("https://provider.example.test/authorize");
    provider.setTokenUri("https://provider.example.test/token"); provider.setUserInfoUri("https://provider.example.test/userinfo");
    provider.setScopes("profile,email"); provider.setUserNameAttribute("id"); provider.setSubjectClaim("id");
    provider.setEmailClaim("email"); provider.setMatchStrategy(ExternalIdentityMatchStrategy.USER_ID);
    provider.setRequireVerifiedEmail(false); provider.setAllowPrivateNetwork(true);
    provider.setSortOrder(17); provider.setEnabled(false); provider.setDescription("original description");
    return provider;
  }

  private ExternalIdentityProvider candidate() {
    var provider = configuration(); provider.setId(id); return provider;
  }

  private String body(Map<String, ?> values) throws Exception { return mapper.writeValueAsString(values); }

  private ExternalIdentityProvider stored() {
    session.flush(); session.clear(); return session.find(ExternalIdentityProvider.class, id);
  }

  private void assertProtectedConfigurationUnchanged() {
    var provider = stored();
    assertEquals(originalCiphertext, provider.getClientSecretCiphertext());
    assertEquals(ORIGINAL_SECRET, cipher.decrypt(provider.getClientSecretCiphertext()));
    assertEquals("isolated-provider", provider.getRegistrationId()); assertEquals("Isolated provider", provider.getDisplayName());
    assertEquals("test-only-client-id", provider.getClientId());
    assertEquals(ExternalIdentityProviderProtocol.OAUTH2, provider.getProtocol());
    assertEquals("https://provider.example.test/authorize", provider.getAuthorizationUri());
    assertEquals(ExternalIdentityMatchStrategy.USER_ID, provider.getMatchStrategy());
    assertFalse(provider.getRequireVerifiedEmail()); assertFalse(provider.getEnabled());
    assertTrue(provider.getAllowPrivateNetwork()); assertEquals(17, provider.getSortOrder());
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (metadataServer != null) metadataServer.stop(0);
    if (session != null) { if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close(); }
    if (factory != null) factory.close();
  }

  @Test
  void readOnlyOAuth2ProbeAllowsDisabledProviderAndReportsItsActualValidationDepth() throws Exception {
    environment.setProperty("server.oauth2.issuer-uri", "https://authorization.example.test/base///");
    mvc.perform(post(PATH + "/" + id + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OAuth2")))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未验证")))
        .andExpect(jsonPath("$.callbackUri").value("https://authorization.example.test/base/login/oauth2/code/isolated-provider"))
        .andExpect(jsonPath("$.authorizationUri").value("https://provider.example.test/authorize"))
        .andExpect(jsonPath("$.clientSecret").doesNotExist()).andExpect(jsonPath("$.clientSecretCiphertext").doesNotExist());
    assertProtectedConfigurationUnchanged();
    assertEquals(0, factory.getStatistics().getEntityUpdateCount()); assertEquals(0, factory.getStatistics().getEntityInsertCount());
  }

  @Test
  void callbackUsesServerIssuerFallbackOrExplicitRelativePathNeverRequestHost() throws Exception {
    environment.setProperty("spring.security.oauth2.authorizationserver.issuer", "https://fallback.example.test/root/");
    assertEquals("https://fallback.example.test/root/login/oauth2/code/isolated-provider", service.testConnection(id).callbackUri());
    environment.setProperty("spring.security.oauth2.authorizationserver.issuer", " ");
    mvc.perform(post(PATH + "/" + id + "/test").header("Host", "frontend.example.test")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.callbackUri").value("/login/oauth2/code/isolated-provider"));
    session.flush();
    assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }

  @Test
  void metadataEditRestoresOmittedProtectedRequiredFieldsBeforeDomainValidation() throws Exception {
    var permissions = new java.util.LinkedHashMap<String, String>();
    for (var field : ExternalIdentityProvider.class.getDeclaredFields()) permissions.put(RESOURCE + field.getName(), "VISIBLE");
    permissions.put(RESOURCE + "description", "EDITABLE"); permissions.put(RESOURCE + "clientId", "MASKED");
    context.setFieldPermissions(permissions);
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("id", id, "description", "edited metadata")))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("edited metadata"))
        .andExpect(jsonPath("$.clientId").value("***"));
    assertProtectedConfigurationUnchanged(); assertEquals("edited metadata", stored().getDescription());
  }

  @Test
  void directServiceCannotDeriveNewCiphertextFromNonWritableClientSecret() {
    context.setFieldPermissions(Map.of(RESOURCE + "clientSecret", "VISIBLE"));
    var candidate = candidate(); candidate.setClientSecret("test-only-forbidden-rotation");
    assertThrows(AccessDeniedException.class, () -> service.modifyById(candidate));
    assertProtectedConfigurationUnchanged(); assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }

  @Test
  void directCreateCannotBypassSecretPolicyThroughDerivedCiphertext() {
    context.setFieldPermissions(Map.of(RESOURCE + "clientSecret", "HIDDEN"));
    var candidate = configuration(); candidate.setRegistrationId("another-provider");
    candidate.setClientSecret("test-only-forbidden-create");
    assertThrows(AccessDeniedException.class, () -> service.create(candidate));
    assertEquals(0, factory.getStatistics().getEntityInsertCount());
    assertEquals(1L, session.createQuery("select count(p) from ExternalIdentityProvider p", Long.class).getSingleResult());
  }

  @Test
  void canonicalEditablePolicyCannotWeakenLegacyMaskedSecretPolicy() {
    context.setFieldPermissions(Map.of(RESOURCE + "clientSecret", "EDITABLE", "ExternalIdentityProvider#clientSecret", "MASKED"));
    var candidate = candidate(); candidate.setClientSecret("test-only-forbidden-legacy-rotation");
    assertThrows(AccessDeniedException.class, () -> service.modifyById(candidate));
    assertProtectedConfigurationUnchanged(); assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }

  @Test
  void publicClientCreationDoesNotRequirePermissionToWriteAnAbsentSecret() {
    context.setFieldPermissions(Map.of(RESOURCE + "clientSecret", "HIDDEN"));
    var candidate = configuration(); candidate.setRegistrationId("public-provider"); candidate.setClientAuthenticationMethod("none");
    var saved = service.create(candidate); session.flush();
    assertNull(saved.getClientSecretCiphertext()); assertFalse(saved.getClientSecretConfigured());
    assertEquals(1, factory.getStatistics().getEntityInsertCount());
  }

  @Test
  void presetCannotValidateOneProtocolThenPersistAnotherProtectedProtocol() {
    context.setFieldPermissions(Map.of(RESOURCE + "protocol", "VISIBLE", RESOURCE + "issuerUri", "VISIBLE"));
    var candidate = candidate(); candidate.setPreset(ExternalIdentityProviderPreset.GOOGLE);
    candidate.setAuthorizationUri(null); candidate.setTokenUri(null); candidate.setUserInfoUri(null);
    assertThrows(IllegalArgumentException.class, () -> service.modifyById(candidate));
    assertProtectedConfigurationUnchanged(); assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }

  @Test
  void blankSecretPreservesCiphertextAndFalseValuesAndRegistrationIsImmutable() throws Exception {
    var candidate = candidate(); candidate.setRegistrationId("not-the-original");
    var payload = mapper.convertValue(candidate, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    payload.put("clientSecret", "  ");
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(payload))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.registrationId").value("isolated-provider"))
        .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.clientSecretConfigured").value(true))
        .andExpect(jsonPath("$.clientSecret").doesNotExist()).andExpect(jsonPath("$.clientSecretCiphertext").doesNotExist());
    assertProtectedConfigurationUnchanged();
  }

  @Test
  void allowedRotationEncryptsActualInputWithoutReturningCredentials() throws Exception {
    var payload = mapper.convertValue(candidate(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    payload.put("clientSecret", "test-only-allowed-rotation");
    var response = mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(payload))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.clientSecret").doesNotExist())
        .andExpect(jsonPath("$.clientSecretCiphertext").doesNotExist()).andReturn().getResponse().getContentAsString();
    var provider = stored(); assertNotEquals(originalCiphertext, provider.getClientSecretCiphertext());
    assertEquals("test-only-allowed-rotation", cipher.decrypt(provider.getClientSecretCiphertext()));
    assertFalse(response.contains("test-only-allowed-rotation")); assertFalse(response.contains(provider.getClientSecretCiphertext()));
  }

  @Test
  void httpProtectedSecretIsRejectedBeforeCredentialDerivation() throws Exception {
    context.setFieldPermissions(Map.of(RESOURCE + "clientSecret", "MASKED"));
    var payload = mapper.convertValue(candidate(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    payload.put("clientSecret", "test-only-forbidden-http-rotation");
    var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(put(PATH)
        .contentType(MediaType.APPLICATION_JSON).content(body(payload))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, error.getCause());
    assertProtectedConfigurationUnchanged();
    assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }

  @Test
  void probeProjectionHonorsSourceEndpointAndRegistrationFieldPolicies() throws Exception {
    context.setFieldPermissions(Map.of(RESOURCE + "authorizationUri", "HIDDEN", RESOURCE + "tokenUri", "MASKED", RESOURCE + "registrationId", "MASKED"));
    mvc.perform(post(PATH + "/" + id + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.authorizationUri").doesNotExist())
        .andExpect(jsonPath("$.tokenUri").value("***")).andExpect(jsonPath("$.callbackUri").value("***"));
    session.flush();
    assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }

  @Test
  void oidcProbeActuallyReadsLocalDiscoveryWithoutTokenOrUserInfoExchange() throws Exception {
    var discoveries = new AtomicInteger(); var unexpectedRequests = new AtomicInteger();
    metadataServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String issuer = "http://127.0.0.1:" + metadataServer.getAddress().getPort();
    metadataServer.createContext("/", exchange -> {
      boolean discovery = exchange.getRequestURI().getPath().equals("/.well-known/openid-configuration") && exchange.getRequestMethod().equals("GET");
      if (discovery) discoveries.incrementAndGet(); else unexpectedRequests.incrementAndGet();
      byte[] json = ("""
          {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
           "userinfo_endpoint":"%s/userinfo","jwks_uri":"%s/jwks","response_types_supported":["code"],
           "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"]}
          """).formatted(issuer, issuer, issuer, issuer, issuer).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(discovery ? 200 : 404, json.length);
      try (var output = exchange.getResponseBody()) { output.write(json); }
    });
    metadataServer.start();
    var provider = session.find(ExternalIdentityProvider.class, id);
    provider.setProtocol(ExternalIdentityProviderProtocol.OIDC); provider.setIssuerUri(issuer);
    provider.setAuthorizationUri(null); provider.setTokenUri(null); provider.setUserInfoUri(null); session.flush();
    factory.getStatistics().clear();
    mvc.perform(post(PATH + "/" + id + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.authorizationUri").value(issuer + "/authorize"))
        .andExpect(jsonPath("$.jwkSetUri").value(issuer + "/jwks"));
    assertEquals(1, discoveries.get()); assertEquals(0, unexpectedRequests.get());
    session.flush();
    assertEquals(0, factory.getStatistics().getEntityUpdateCount());
    context.setFieldPermissions(Map.of(RESOURCE + "issuerUri", "HIDDEN"));
    mvc.perform(post(PATH + "/" + id + "/test").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.authorizationUri").doesNotExist())
        .andExpect(jsonPath("$.tokenUri").doesNotExist()).andExpect(jsonPath("$.userInfoUri").doesNotExist())
        .andExpect(jsonPath("$.jwkSetUri").doesNotExist());
    assertEquals(2, discoveries.get()); assertEquals(0, unexpectedRequests.get());
    session.flush(); assertEquals(0, factory.getStatistics().getEntityUpdateCount());
  }
}
