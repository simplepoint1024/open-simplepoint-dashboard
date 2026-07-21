package org.simplepoint.security.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Persists resource scopes as a stable, comma-separated enum set. */
@Converter
public class ResourceScopeTypesConverter implements AttributeConverter<Set<ResourceScopeType>, String> {

  @Override
  public String convertToDatabaseColumn(final Set<ResourceScopeType> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return null;
    }
    return attribute.stream()
        .sorted()
        .map(Enum::name)
        .collect(Collectors.joining(","));
  }

  @Override
  public Set<ResourceScopeType> convertToEntityAttribute(final String value) {
    if (value == null || value.isBlank()) {
      return Collections.emptySet();
    }
    EnumSet<ResourceScopeType> scopes = EnumSet.noneOf(ResourceScopeType.class);
    Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .map(ResourceScopeType::valueOf)
        .forEach(scopes::add);
    return scopes;
  }
}
