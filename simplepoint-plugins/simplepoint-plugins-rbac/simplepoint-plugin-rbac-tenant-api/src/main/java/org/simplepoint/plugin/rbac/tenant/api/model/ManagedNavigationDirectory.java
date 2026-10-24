package org.simplepoint.plugin.rbac.tenant.api.model;

import org.simplepoint.api.locale.LocalizedText;

/** Available structural tenant parent; executable permissions are not granted by selecting it. */
public record ManagedNavigationDirectory(
    String id, String code, String name, LocalizedText nameLocalization) {}
