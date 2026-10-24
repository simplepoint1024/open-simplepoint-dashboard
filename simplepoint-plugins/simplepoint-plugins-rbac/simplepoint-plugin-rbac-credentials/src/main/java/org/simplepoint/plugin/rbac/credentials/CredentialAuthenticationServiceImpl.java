package org.simplepoint.plugin.rbac.credentials;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.simplepoint.plugin.rbac.authentication.AccountCredentialVerifier;
import org.simplepoint.plugin.rbac.authentication.AuthenticationAccountState;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialIdentity;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialRequest;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialSession;
import org.simplepoint.plugin.rbac.core.api.service.CredentialAuthenticationService;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.entity.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** The identity owner verifies credentials, durably audits, and issues caller-bound proofs. */
@Service
@RemoteProvider
public class CredentialAuthenticationServiceImpl implements CredentialAuthenticationService {
  private final AccountCredentialVerifier verifier;
  private final CredentialTickets tickets;
  private final CredentialAudit audit;
  private final UserManagementService users;

  /** Keeps factor policy, proof storage and durable audit independently testable. */
  public CredentialAuthenticationServiceImpl(
      AccountCredentialVerifier verifier,
      CredentialTickets tickets,
      CredentialAudit audit,
      UserManagementService users) {
    this.verifier = verifier;
    this.tickets = tickets;
    this.audit = audit;
    this.users = users;
  }

  @Override
  public CredentialSession open(CredentialRequest request) {
    String caller = caller();
    String fingerprint = fingerprint(request == null ? null : request.username());
    User user;
    try {
      user =
          verifier.verify(
              request == null ? null : request.username(),
              request == null ? null : request.password(),
              request == null ? null : request.oneTimeCode());
    } catch (AuthenticationException failure) {
      Code code =
          failure instanceof AuthenticationServiceException ? Code.UNAVAILABLE : Code.INVALID;
      record(caller, null, fingerprint, "FAILURE", code.name());
      throw new CredentialAuthenticationException(code);
    } catch (RuntimeException failure) {
      record(caller, null, fingerprint, "FAILURE", "UNAVAILABLE");
      throw new CredentialAuthenticationException(Code.UNAVAILABLE);
    }
    CredentialSession session;
    try {
      session = tickets.issue(caller, user);
    } catch (RuntimeException unavailable) {
      record(caller, user.getId(), fingerprint, "FAILURE", "UNAVAILABLE");
      throw new CredentialAuthenticationException(Code.UNAVAILABLE);
    }
    try {
      record(caller, user.getId(), fingerprint, "SUCCESS", "VERIFIED");
      return session;
    } catch (RuntimeException failure) {
      try {
        tickets.revoke(caller, session.handle());
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public CredentialIdentity check(String handle) {
    String caller = caller();
    var ticket = tickets.read(caller, handle);
    try {
      User current =
          AuthenticationAccountState.requireActive(
              users.findByIdForAuthorization(ticket.userId()).orElse(null));
      if (current.getAuthorizationVersion() != ticket.version()) {
        throw new CredentialAuthenticationException(Code.SESSION_INVALID);
      }
      return new CredentialIdentity(
          current.getId(),
          current.getAuthorizationVersion(),
          Boolean.TRUE.equals(current.superAdmin()),
          Instant.ofEpochMilli(ticket.expiresAt()));
    } catch (AuthenticationException | CredentialAuthenticationException rejected) {
      tickets.revoke(caller, handle);
      throw new CredentialAuthenticationException(Code.SESSION_INVALID);
    } catch (RuntimeException unavailable) {
      throw new CredentialAuthenticationException(Code.UNAVAILABLE);
    }
  }

  @Override
  public void close(String handle) {
    tickets.revoke(caller(), handle);
  }

  private void record(
      String caller, String userId, String fingerprint, String status, String reason) {
    try {
      audit.record(
          new CredentialAuditContract.Outcome(
              caller, userId, fingerprint, status, reason, Instant.now().toEpochMilli()));
    } catch (RuntimeException unavailable) {
      throw new CredentialAuthenticationException(Code.UNAVAILABLE);
    }
  }

  private static String caller() {
    return ServiceClientIdentity.authenticatedClientId(
            SecurityContextHolder.getContext().getAuthentication())
        .filter(client -> client.getBytes(StandardCharsets.UTF_8).length <= 160)
        .orElseThrow(
            () -> new AccessDeniedException("Credential authentication requires a service client"));
  }

  private static String fingerprint(String subject) {
    String bounded =
        subject == null || subject.length() > 254
            ? "<invalid>"
            : subject.trim().toLowerCase(Locale.ROOT);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(bounded.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
