package org.simplepoint.plugin.i18n.api.model;

/** Message metadata accepted by registration; identity is locale, namespace and code. */
public record MessageDeclaration(
    String locale, String namespace, String code, String message, String description, Boolean global) {
}
