package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.repository.I18nCountriesRepository;
import org.simplepoint.plugin.i18n.api.service.I18nCountriesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CountriesServiceImpl provides the implementation for CountriesService.
 * It extends BaseServiceImpl to manage Countries entities.
 * This service is used to interact with the persistence layer for country entities.
 */
@Service
public class I18nCountriesServiceImpl extends BaseServiceImpl<I18nCountriesRepository, Countries, String>
    implements I18nCountriesService {

  /**
   * 构造函数，使用指定的存储库、用户上下文和详细信息提供服务构造 BaseServiceImpl。
   * Constructor that constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                 用于实体操作的存储库
   * @param detailsProviderService     提供额外详细信息的服务
   */
  public I18nCountriesServiceImpl(
      I18nCountriesRepository repository,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
  }
}
