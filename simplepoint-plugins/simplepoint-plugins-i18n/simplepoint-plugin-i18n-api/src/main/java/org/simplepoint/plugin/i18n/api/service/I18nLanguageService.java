package org.simplepoint.plugin.i18n.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.i18n.api.entity.Language;

/**
 * Local language management; only the inherited directory lookup is a remote capability.
 */
public interface I18nLanguageService extends BaseService<Language, String>, I18nLanguageDirectory {
}
