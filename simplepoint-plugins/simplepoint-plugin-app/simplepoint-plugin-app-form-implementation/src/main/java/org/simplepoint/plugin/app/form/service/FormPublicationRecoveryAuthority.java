package org.simplepoint.plugin.app.form.service;

/** Host adapter resolves current permissions for stored identity references; never an authorization snapshot. */
public interface FormPublicationRecoveryAuthority {
  /** Opens a validated tenant/actor/role context for one bounded attempt. */
  Scope open(String tenantId, String actorId, String roleId);

  /** Restores all thread context, including on failure. */
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
