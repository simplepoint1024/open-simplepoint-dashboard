/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerTest {

  @Test
  void canInstantiate() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    assertThat(handler).isNotNull();
  }

  @Test
  void decimalFailureHasAStableSafeClientError() {
    var response =
        new GlobalExceptionHandler()
            .invalidDecimalValue(new org.simplepoint.core.base.InvalidDecimalValueException());
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).containsEntry("code", "INVALID_DECIMAL_VALUE");
  }

  @Test
  void actualValidRequestBodyAndExplicitFormValidationShareSafeLanguageNeutralHttpErrors()
      throws Exception {
    try (var validator =
        new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean()) {
      validator.afterPropertiesSet();
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new FormController())
              .setValidator(validator)
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
      for (String path : java.util.List.of("/valid-body", "/form-write")) {
        var response =
            mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"private-invalid-input\"}"))
                .andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isBadRequest())
                .andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                            "$.code")
                        .value("VALIDATION_FAILED"))
                .andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                            "$.fieldErrors[0].field")
                        .value("code"))
                .andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                            "$.fieldErrors[0].keyword")
                        .value("pattern"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response)
            .doesNotContain("private-invalid-input", "[A-Z]", "must match", "ConstraintViolation");
      }
    }
  }

  public record Input(@jakarta.validation.constraints.Pattern(regexp = "[A-Z]+") String code) {}

  @org.springframework.web.bind.annotation.RestController
  static class FormController {
    // Existing local business handlers must not consume the platform validation contract.
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<?> business(IllegalArgumentException error) {
      return org.springframework.http.ResponseEntity.badRequest()
          .body(java.util.Map.of("code", "LOCAL_BUSINESS_ERROR"));
    }

    @org.springframework.web.bind.annotation.PostMapping("/valid-body")
    public Object body(
        @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            Input input) {
      return input;
    }

    @org.springframework.web.bind.annotation.PostMapping("/form-write")
    public Object form(@org.springframework.web.bind.annotation.RequestBody Input input) {
      try (var validator = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
        org.simplepoint.core.validation.DeclarativeFormValidation.validate(
            input, java.util.Set.of(), validator.getValidator());
      }
      return input;
    }
  }
}
