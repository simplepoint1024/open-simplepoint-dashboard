package org.simplepoint.plugin.rbac.tenant.api.error;

/** Bounded public error, excluding user-entered names or translations. */
public final class LocalizedResourceNameException extends RuntimeException {
  public LocalizedResourceNameException() { super("tenant.resources.localizedNameInvalid"); }
}
