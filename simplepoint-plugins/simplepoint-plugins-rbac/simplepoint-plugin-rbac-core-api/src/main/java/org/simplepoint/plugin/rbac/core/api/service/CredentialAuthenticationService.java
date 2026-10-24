package org.simplepoint.plugin.rbac.core.api.service;

import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialIdentity;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialRequest;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialSession;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteOperation;

/** Authentication remains inside identity; consumers never compare password hashes. */
@RemoteContract(
    name = "security.credentials",
    errorDecoder = CredentialAuthenticationException.Decoder.class)
public interface CredentialAuthenticationService {
  /**
   * Consumes attempt/MFA budgets and audits the outcome; never replay after an uncertain response.
   */
  CredentialSession open(CredentialRequest request);

  /** Revalidates the same caller's proof against expiry and current account/version policy. */
  @RemoteOperation(idempotent = true)
  CredentialIdentity check(String handle);

  /** Revokes only a proof issued to the authenticated calling service. */
  @RemoteOperation(idempotent = true)
  void close(String handle);
}
