package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;

/**
 * Service for dictionaries.
 */
public interface DictionaryService extends BaseService<Dictionary, String> {

  Collection<DictionaryOptionVo> options(String dictionaryCode);
}
