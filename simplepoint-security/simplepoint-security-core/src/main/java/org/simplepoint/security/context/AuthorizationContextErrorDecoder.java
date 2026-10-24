package org.simplepoint.security.context;

import org.simplepoint.remoting.RemoteErrorDecoder;

/** Accepts only this contract's exact codes and never trusts a remote message as public content. */
public final class AuthorizationContextErrorDecoder implements RemoteErrorDecoder {
  @Override
  public AuthorizationContextRejection decode(final String code, final String message) {
    if ("AUTHORIZATION_CONTEXT:ACCESS_DENIED".equals(code)) {
      return new AuthorizationContextRejection(AuthorizationContextRejection.Reason.ACCESS_DENIED);
    }
    if ("AUTHORIZATION_CONTEXT:AUTHENTICATION_REQUIRED".equals(code)) {
      return new AuthorizationContextRejection(AuthorizationContextRejection.Reason.AUTHENTICATION_REQUIRED);
    }
    return null;
  }
}
