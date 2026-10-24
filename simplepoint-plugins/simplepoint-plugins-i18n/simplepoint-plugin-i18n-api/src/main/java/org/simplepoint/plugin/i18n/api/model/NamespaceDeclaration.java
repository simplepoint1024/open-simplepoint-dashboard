package org.simplepoint.plugin.i18n.api.model;

/** Namespace metadata accepted by registration; no persistence or audit fields. */
public record NamespaceDeclaration(String code, String name, String module, String description) {
}
