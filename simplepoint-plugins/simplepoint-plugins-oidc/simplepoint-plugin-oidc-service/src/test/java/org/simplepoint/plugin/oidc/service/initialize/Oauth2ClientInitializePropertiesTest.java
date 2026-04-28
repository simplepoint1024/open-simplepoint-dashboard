package org.simplepoint.plugin.oidc.service.initialize;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Oauth2ClientInitializePropertiesTest {

  @Test
  void validatePasses_whenRegistrationHasClientId() {
    Oauth2ClientInitializeProperties props = new Oauth2ClientInitializeProperties();
    Oauth2ClientInitializeProperties.Registration reg = new Oauth2ClientInitializeProperties.Registration();
    reg.setClientId("my-client");
    props.getRegistration().put("test", reg);
    assertDoesNotThrow(props::validate);
  }

  @Test
  void validateThrows_whenClientIdIsNull() {
    Oauth2ClientInitializeProperties props = new Oauth2ClientInitializeProperties();
    Oauth2ClientInitializeProperties.Registration reg = new Oauth2ClientInitializeProperties.Registration();
    props.getRegistration().put("test", reg);
    IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
    assertTrue(ex.getMessage().contains("test"));
  }

  @Test
  void validateThrows_whenClientIdIsBlank() {
    Oauth2ClientInitializeProperties props = new Oauth2ClientInitializeProperties();
    Oauth2ClientInitializeProperties.Registration reg = new Oauth2ClientInitializeProperties.Registration();
    reg.setClientId("   ");
    props.getRegistration().put("myReg", reg);
    assertThrows(IllegalStateException.class, props::validate);
  }

  @Test
  void validatePasses_whenNoRegistrations() {
    Oauth2ClientInitializeProperties props = new Oauth2ClientInitializeProperties();
    assertDoesNotThrow(props::validate);
  }

  @Test
  void registrationGettersSetters() {
    Oauth2ClientInitializeProperties.Registration reg = new Oauth2ClientInitializeProperties.Registration();
    reg.setProvider("google");
    reg.setClientId("cid");
    reg.setClientSecret("secret");
    reg.setClientAuthenticationMethod("basic");
    reg.setAuthorizationGrantType("authorization_code");
    reg.setRedirectUri("http://localhost/callback");
    reg.setScope(Set.of("openid", "profile"));
    reg.setClientName("My App");

    assertEquals("google", reg.getProvider());
    assertEquals("cid", reg.getClientId());
    assertEquals("secret", reg.getClientSecret());
    assertEquals("basic", reg.getClientAuthenticationMethod());
    assertEquals("authorization_code", reg.getAuthorizationGrantType());
    assertEquals("http://localhost/callback", reg.getRedirectUri());
    assertTrue(reg.getScope().contains("openid"));
    assertEquals("My App", reg.getClientName());
  }

  @Test
  void providerGettersSetters() {
    Oauth2ClientInitializeProperties.Provider provider = new Oauth2ClientInitializeProperties.Provider();
    provider.setAuthorizationUri("https://auth.example.com/auth");
    provider.setJwkSetUri("https://auth.example.com/jwks");
    assertEquals("https://auth.example.com/auth", provider.getAuthorizationUri());
    assertEquals("https://auth.example.com/jwks", provider.getJwkSetUri());
  }

  @Test
  void mapsAreInitializedOnConstruction() {
    Oauth2ClientInitializeProperties props = new Oauth2ClientInitializeProperties();
    assertNotNull(props.getRegistration());
    assertNotNull(props.getProvider());
    assertTrue(props.getRegistration().isEmpty());
    assertTrue(props.getProvider().isEmpty());
  }

  @Test
  void providerMapCanBePopulated() {
    Oauth2ClientInitializeProperties props = new Oauth2ClientInitializeProperties();
    Oauth2ClientInitializeProperties.Provider p = new Oauth2ClientInitializeProperties.Provider();
    p.setJwkSetUri("https://example.com/jwks");
    props.getProvider().put("keycloak", p);
    assertEquals(p, props.getProvider().get("keycloak"));
  }
}
