package org.simplepoint.plugin.i18n.api.service;

import java.util.Map;
import org.simplepoint.remoting.RemoteContract;

/** Public language lookup, without persistence or management operations. */
@RemoteContract(name = "i18n.language")
public interface I18nLanguageDirectory {

  /** Returns language codes and display names. */
  Map<String, String> mapping();
}
