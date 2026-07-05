package org.simplepoint.plugin.oidc.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.oidc.api.pojo.dto.OidcClientConfigurationDto;
import org.springframework.web.bind.annotation.PathVariable;

class OidcClientControllerTest {

  @Test
  void configurationPathVariablesDeclareExplicitNames() throws NoSuchMethodException {
    PathVariable getId = OidcClientController.class
        .getMethod("configuration", String.class)
        .getParameters()[0]
        .getAnnotation(PathVariable.class);
    PathVariable putId = OidcClientController.class
        .getMethod("modifyConfiguration", String.class, OidcClientConfigurationDto.class)
        .getParameters()[0]
        .getAnnotation(PathVariable.class);

    assertEquals("id", getId.value());
    assertEquals("id", putId.value());
  }
}
