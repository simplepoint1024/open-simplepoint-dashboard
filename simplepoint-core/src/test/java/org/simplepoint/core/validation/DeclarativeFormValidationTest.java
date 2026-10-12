package org.simplepoint.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeclarativeFormValidationTest {
  @Test
  void existingNonNullEditableTextCannotOmitInputButReadonlyAndExplicitOptionalRemainServerOwned() {
    var input = new PersistedInput();
    assertThatThrownBy(() -> DeclarativeFormValidation.validate(input, Set.of(), null))
        .isInstanceOfSatisfying(
            InvalidFormException.class,
            error ->
                assertThat(error.getViolations())
                    .extracting(FormViolation::field)
                    .containsExactly("name"));
    input.name = "Name";
    DeclarativeFormValidation.validate(input, Set.of(), null);
  }

  static class PersistedInput {
    @jakarta.persistence.Column(nullable = false)
    @Schema(minLength = 1)
    String name;

    @jakarta.persistence.Column(nullable = false)
    @Schema(minLength = 1, accessMode = Schema.AccessMode.READ_ONLY)
    String derived;

    @jakarta.persistence.Column(nullable = false)
    @Schema(minLength = 1, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    String optional;

    @jakarta.persistence.Column(nullable = false)
    String audit;

    @Schema(minLength = 1)
    String optionalText;
  }

  @Test
  void absentFieldsCannotBypassRequiredButOptionalRulesDoNotBecomeRequired() {
    Input input = new Input();
    assertThatThrownBy(() -> DeclarativeFormValidation.validate(input, Set.of(), null))
        .isInstanceOfSatisfying(
            InvalidFormException.class,
            error ->
                assertThat(error.getViolations())
                    .extracting(FormViolation::keyword)
                    .containsExactly("required"));
    input.code = "OK";
    DeclarativeFormValidation.validate(input, Set.of(), null);
    input.optional = "";
    assertThatThrownBy(() -> DeclarativeFormValidation.validate(input, Set.of(), null))
        .isInstanceOfSatisfying(
            InvalidFormException.class,
            error ->
                assertThat(error.getViolations())
                    .extracting(FormViolation::keyword)
                    .containsExactly("minLength"));
  }

  @Test
  void nativeAnnotationValidationIsCombinedWithSchemaRulesWithoutValuesOrRegexInErrors() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      Input input = new Input();
      input.code = "secret-invalid";
      input.amount = 2;
      input.title = " ";
      input.alias = "BAD";
      assertThatThrownBy(
              () -> DeclarativeFormValidation.validate(input, Set.of(), factory.getValidator()))
          .isInstanceOfSatisfying(
              InvalidFormException.class,
              error -> {
                assertThat(error.getViolations())
                    .extracting(FormViolation::keyword)
                    .contains("pattern", "required", "exclusiveMinimum");
                assertThat(error.getViolations().toString())
                    .doesNotContain("secret-invalid", "^[A-Z]", "BAD");
                assertThat(error.getViolations())
                    .anySatisfy(
                        violation ->
                            assertThat(violation.messageKey()).isEqualTo("form.example.alias"));
              });
      input.code = "OK";
      input.amount = 3;
      input.title = "Valid";
      input.alias = "abc";
      DeclarativeFormValidation.validate(input, Set.of(), factory.getValidator());
    }
  }

  @Test
  void protectedFieldsAreNotValidatedAsEditableInputAndUnicodeLengthMatchesJsonSchema() {
    Input input = new Input();
    input.code = "***";
    input.optional = "😀";
    assertThatThrownBy(() -> DeclarativeFormValidation.validate(input, Set.of("code"), null))
        .isInstanceOfSatisfying(
            InvalidFormException.class,
            error ->
                assertThat(error.getViolations())
                    .extracting(FormViolation::field)
                    .containsExactly("optional"));
    input.optional = "😀😀";
    DeclarativeFormValidation.validate(input, Set.of("code"), null);
  }

  static class Input {
    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        minLength = 2,
        maxLength = 8,
        pattern = "^[A-Z]+$")
    String code;

    @Schema(minLength = 2)
    String optional;

    @Schema(minimum = "2", exclusiveMinimum = true, maximum = "5")
    Integer amount;

    @NotBlank String title;

    @Pattern(regexp = "[a-z]+", message = "i18n:form.example.alias")
    String alias;
  }
}
