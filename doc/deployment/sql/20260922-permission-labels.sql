-- Update only previous built-in wording; preserve administrator-customized translations.
BEGIN;
UPDATE simpoint_i18n_messages SET message = '组织管理' WHERE locale = 'zh-CN' AND code = 'menu.platform.organizations' AND message = '组织机构管理';
UPDATE simpoint_i18n_messages SET message = 'Select the organizations allowed in a custom organization scope' WHERE locale = 'en-US' AND code = 'data-scopes.description.customDeptIds' AND message = 'Set of department IDs explicitly granted access; only used when scope type is CUSTOM';
UPDATE simpoint_i18n_messages SET message = 'Organizations' WHERE locale = 'en-US' AND code = 'data-scopes.preview.depts' AND message = 'Organization IDs';
UPDATE simpoint_i18n_messages SET message = '选择允许访问的组织；仅在自定义组织范围下生效' WHERE locale = 'zh-CN' AND code = 'data-scopes.description.customDeptIds' AND message = '明确授权的部门 ID 集合；仅在范围类型为「自定义部门」时生效';
UPDATE simpoint_i18n_messages SET message = '组织' WHERE locale = 'zh-CN' AND code = 'data-scopes.preview.depts' AND message = '组织 ID';
UPDATE simpoint_i18n_messages SET message = '此资源暂不支持数据权限配置。' WHERE locale = 'zh-CN' AND code = 'roles.resourceConfig.dataUnsupported' AND message = '此页面尚未接入 ORM 数据范围过滤。';
UPDATE simpoint_i18n_messages SET message = 'Match all' WHERE locale = 'en-US' AND code = 'table.query.and' AND message = 'Match all (AND)';
UPDATE simpoint_i18n_messages SET message = 'Match any' WHERE locale = 'en-US' AND code = 'table.query.or' AND message = 'Match any (OR)';
UPDATE simpoint_i18n_messages SET message = 'Does not match' WHERE locale = 'en-US' AND code = 'table.query.not' AND message = 'Does not match (NOT)';
UPDATE simpoint_i18n_messages SET message = '满足全部' WHERE locale = 'zh-CN' AND code = 'table.query.and' AND message = '满足全部（AND）';
UPDATE simpoint_i18n_messages SET message = '满足任一' WHERE locale = 'zh-CN' AND code = 'table.query.or' AND message = '满足任一（OR）';
UPDATE simpoint_i18n_messages SET message = '不满足' WHERE locale = 'zh-CN' AND code = 'table.query.not' AND message = '不满足（NOT）';
COMMIT;
