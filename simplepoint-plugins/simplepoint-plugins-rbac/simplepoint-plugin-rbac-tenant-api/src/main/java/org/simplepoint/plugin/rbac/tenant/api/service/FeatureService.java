package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;

/**
 * FeatureService is an interface that defines methods for managing feature-related operations.
 * It is part of the RBAC (Role-Based Access Control) module of the SimplePoint application.
 * This service will handle operations related to features, such as retrieving feature information,
 * managing feature data, and other related functionalities.
 */
public interface FeatureService extends BaseService<Feature, String> {
}
