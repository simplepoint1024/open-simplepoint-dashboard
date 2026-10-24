package org.simplepoint.plugin.rbac.core.service.configuration;

import org.simplepoint.security.entity.User;

/** Internal startup-only account creation port; never exposed in UserManagementService or RPC. */
public interface SystemUserBootstrap {
  /** Seeds an account with trusted startup policy before resource declarations; personal workspace preparation occurs on first login. */
  User createBootstrapAccount(User account);
}
