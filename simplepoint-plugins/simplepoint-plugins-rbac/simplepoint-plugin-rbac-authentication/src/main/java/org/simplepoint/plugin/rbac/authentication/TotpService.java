package org.simplepoint.plugin.rbac.authentication;

import org.springframework.stereotype.Service;

/** Shared TOTP implementation used by login and platform step-up verification. */
@Service
public class TotpService extends org.simplepoint.security.authentication.TotpVerifier {}
