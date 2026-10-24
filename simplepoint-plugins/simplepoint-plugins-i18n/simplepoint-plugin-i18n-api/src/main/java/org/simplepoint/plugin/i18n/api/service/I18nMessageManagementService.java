package org.simplepoint.plugin.i18n.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.locale.I18nMessageService;

/** Local entity management, deliberately separate from the public message query contract. */
public interface I18nMessageManagementService extends BaseService<Message, String>, I18nMessageService {
}
