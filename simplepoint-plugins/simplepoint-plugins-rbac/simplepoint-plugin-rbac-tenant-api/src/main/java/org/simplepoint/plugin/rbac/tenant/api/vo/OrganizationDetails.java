package org.simplepoint.plugin.rbac.tenant.api.vo;

import java.util.Map;

/** Authorized organization values and minimal, field-scoped reference labels. */
public record OrganizationDetails(Map<String, Object> record, Map<String, Map<String, String>> references) {}
