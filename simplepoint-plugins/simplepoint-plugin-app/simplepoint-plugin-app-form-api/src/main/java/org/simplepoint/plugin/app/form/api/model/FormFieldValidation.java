package org.simplepoint.plugin.app.form.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Optional business text constraints, separate from physical column capacity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormFieldValidation(Integer minLength, Integer maxLength, String pattern, String patternMessage) {}
