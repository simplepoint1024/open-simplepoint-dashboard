package org.simplepoint.plugin.app.form.api.error;

import org.simplepoint.remoting.RemoteErrorDecoder;

/** Typed errors at the trusted APP form integration boundary. */
public final class ApplicationFormErrorDecoder implements RemoteErrorDecoder {
  @Override
  public ApplicationFormException decode(String code, String message) {
    if (code == null || !code.startsWith("APP_FORM:")) {
      return null;
    }
    try {
      return new ApplicationFormException(
          ApplicationFormErrorCode.valueOf(code.substring(9)), message);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
