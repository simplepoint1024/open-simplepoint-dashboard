import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableSubmitAction} from '@simplepoint/components/SimpleTable/types';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {message, Tag} from 'antd';
import React, {useCallback, useEffect, useMemo, useState} from 'react';

const baseConfig = api['oidc-clients'];

type OidcClientConfiguration = {
  id?: string;
  clientId?: string;
  clientIdIssuedAt?: string;
  clientName?: string;
  clientSecret?: string;
  clientSecretExpiresAt?: string;
  clientAuthenticationMethods?: string[];
  authorizationGrantTypes?: string[];
  redirectUris?: string[];
  postLogoutRedirectUris?: string[];
  scopes?: string[];
  requireProofKey?: boolean;
  requireAuthorizationConsent?: boolean;
  jwkSetUrl?: string;
  tokenEndpointAuthenticationSigningAlgorithm?: string;
  reuseRefreshTokens?: boolean;
  x509CertificateBoundAccessTokens?: boolean;
  idTokenSignatureAlgorithm?: string;
  accessTokenFormat?: string;
  accessTokenTtlSeconds?: number;
  refreshTokenTtlSeconds?: number;
  authorizationCodeTtlSeconds?: number;
  deviceCodeTtlSeconds?: number;
};

type OidcClientFormData = {
  id?: string;
  clientId?: string;
  clientIdIssuedAt?: string;
  clientName?: string;
  clientSecret?: string;
  clientSecretExpiresAt?: string;
  clientAuthenticationMethods?: string;
  authorizationGrantTypes?: string;
  redirectUris?: string;
  postLogoutRedirectUris?: string;
  scopes?: string;
  requireProofKey?: boolean;
  requireAuthorizationConsent?: boolean;
  jwkSetUrl?: string;
  tokenEndpointAuthenticationSigningAlgorithm?: string;
  reuseRefreshTokens?: boolean;
  x509CertificateBoundAccessTokens?: boolean;
  idTokenSignatureAlgorithm?: string;
  accessTokenFormat?: string;
  accessTokenTtlMinutes?: number;
  refreshTokenTtlHours?: number;
  authorizationCodeTtlMinutes?: number;
  deviceCodeTtlMinutes?: number;
};

const defaultFormData: OidcClientFormData = {
  clientAuthenticationMethods: 'client_secret_basic',
  authorizationGrantTypes: 'authorization_code,refresh_token',
  scopes: 'openid,profile',
  requireProofKey: true,
  requireAuthorizationConsent: true,
  tokenEndpointAuthenticationSigningAlgorithm: 'PS256',
  reuseRefreshTokens: true,
  x509CertificateBoundAccessTokens: false,
  idTokenSignatureAlgorithm: 'PS256',
  accessTokenFormat: 'self-contained',
  accessTokenTtlMinutes: 30,
  refreshTokenTtlHours: 8,
  authorizationCodeTtlMinutes: 5,
  deviceCodeTtlMinutes: 5,
};

const parseCsv = (value?: string | string[]) => {
  if (!value) return [];
  const source = Array.isArray(value) ? value.join(',') : value;
  return source
    .split(/[,\n\r]+/)
    .map((item) => item.trim())
    .filter(Boolean);
};

const formatCsv = (value?: string[] | string) => {
  if (Array.isArray(value)) {
    return value.join(',');
  }
  return value ?? '';
};

const secondsToMinutes = (seconds?: number, fallback = 5) => Math.max(1, Math.round((seconds ?? fallback * 60) / 60));

const secondsToHours = (seconds?: number, fallback = 8) => Math.max(1, Math.round((seconds ?? fallback * 3600) / 3600));

const minutesToSeconds = (minutes?: number, fallback = 5) => Math.max(1, Math.round(minutes ?? fallback)) * 60;

const hoursToSeconds = (hours?: number, fallback = 8) => Math.max(1, Math.round(hours ?? fallback)) * 3600;

const renderCsvTags = (value?: string) => {
  const items = parseCsv(value);
  if (items.length === 0) return null;
  return (
    <span style={{display: 'flex', flexWrap: 'wrap', gap: 4}}>
      {items.map((item) => (
        <Tag key={item} style={{marginInlineEnd: 0}}>
          {item}
        </Tag>
      ))}
    </span>
  );
};

const configurationToForm = (configuration: OidcClientConfiguration): OidcClientFormData => ({
  ...defaultFormData,
  id: configuration.id,
  clientId: configuration.clientId,
  clientIdIssuedAt: configuration.clientIdIssuedAt,
  clientName: configuration.clientName,
  clientSecret: '',
  clientSecretExpiresAt: configuration.clientSecretExpiresAt,
  clientAuthenticationMethods: formatCsv(configuration.clientAuthenticationMethods),
  authorizationGrantTypes: formatCsv(configuration.authorizationGrantTypes),
  redirectUris: formatCsv(configuration.redirectUris),
  postLogoutRedirectUris: formatCsv(configuration.postLogoutRedirectUris),
  scopes: formatCsv(configuration.scopes),
  requireProofKey: configuration.requireProofKey ?? defaultFormData.requireProofKey,
  requireAuthorizationConsent: configuration.requireAuthorizationConsent ?? defaultFormData.requireAuthorizationConsent,
  jwkSetUrl: configuration.jwkSetUrl,
  tokenEndpointAuthenticationSigningAlgorithm:
    configuration.tokenEndpointAuthenticationSigningAlgorithm ?? defaultFormData.tokenEndpointAuthenticationSigningAlgorithm,
  reuseRefreshTokens: configuration.reuseRefreshTokens ?? defaultFormData.reuseRefreshTokens,
  x509CertificateBoundAccessTokens:
    configuration.x509CertificateBoundAccessTokens ?? defaultFormData.x509CertificateBoundAccessTokens,
  idTokenSignatureAlgorithm: configuration.idTokenSignatureAlgorithm ?? defaultFormData.idTokenSignatureAlgorithm,
  accessTokenFormat: configuration.accessTokenFormat ?? defaultFormData.accessTokenFormat,
  accessTokenTtlMinutes: secondsToMinutes(configuration.accessTokenTtlSeconds, 30),
  refreshTokenTtlHours: secondsToHours(configuration.refreshTokenTtlSeconds, 8),
  authorizationCodeTtlMinutes: secondsToMinutes(configuration.authorizationCodeTtlSeconds, 5),
  deviceCodeTtlMinutes: secondsToMinutes(configuration.deviceCodeTtlSeconds, 5),
});

const formToConfiguration = (
  formData: OidcClientFormData,
  currentEditing: OidcClientFormData | null
): OidcClientConfiguration => ({
  id: currentEditing?.id ?? formData.id,
  clientId: formData.clientId,
  clientIdIssuedAt: currentEditing?.clientIdIssuedAt ?? formData.clientIdIssuedAt,
  clientName: formData.clientName,
  clientSecret: formData.clientSecret?.trim() || undefined,
  clientSecretExpiresAt: formData.clientSecretExpiresAt || undefined,
  clientAuthenticationMethods: parseCsv(formData.clientAuthenticationMethods),
  authorizationGrantTypes: parseCsv(formData.authorizationGrantTypes),
  redirectUris: parseCsv(formData.redirectUris),
  postLogoutRedirectUris: parseCsv(formData.postLogoutRedirectUris),
  scopes: parseCsv(formData.scopes),
  requireProofKey: formData.requireProofKey,
  requireAuthorizationConsent: formData.requireAuthorizationConsent,
  jwkSetUrl: formData.jwkSetUrl?.trim() || undefined,
  tokenEndpointAuthenticationSigningAlgorithm: formData.tokenEndpointAuthenticationSigningAlgorithm,
  reuseRefreshTokens: formData.reuseRefreshTokens,
  x509CertificateBoundAccessTokens: formData.x509CertificateBoundAccessTokens,
  idTokenSignatureAlgorithm: formData.idTokenSignatureAlgorithm,
  accessTokenFormat: formData.accessTokenFormat,
  accessTokenTtlSeconds: minutesToSeconds(formData.accessTokenTtlMinutes, 30),
  refreshTokenTtlSeconds: hoursToSeconds(formData.refreshTokenTtlHours, 8),
  authorizationCodeTtlSeconds: minutesToSeconds(formData.authorizationCodeTtlMinutes, 5),
  deviceCodeTtlSeconds: minutesToSeconds(formData.deviceCodeTtlMinutes, 5),
});

const App = () => {
  const {ensure, locale, t} = useI18n();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<OidcClientFormData | null>(null);
  const [initialValues, setInitialValues] = useState<OidcClientFormData>(defaultFormData);

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, 'table', 'common']);
  }, [ensure, locale]);

  const addTypedField = useCallback((properties: Record<string, any>, key: string, field: Record<string, any>) => {
    properties[key] = field;
  }, []);

  const formSchemaTransform = useCallback((schema: any, currentEditing: OidcClientFormData | null) => {
    const next = structuredClone(schema);
    const properties = next?.properties ?? {};

    delete properties.clientSettings;
    delete properties.tokenSettings;
    delete properties.clientIdIssuedAt;

    const required = Array.isArray(next.required)
      ? next.required.filter((key: string) => !['clientSettings', 'tokenSettings', 'clientIdIssuedAt'].includes(key))
      : [];
    next.required = currentEditing ? required.filter((key: string) => key !== 'clientSecret') : required;

    if (properties.clientSecret && currentEditing) {
      properties.clientSecret.description = t('clients.description.clientSecret.edit', '留空表示保持现有密钥不变');
    }
    if (properties.redirectUris) {
      properties.redirectUris['x-ui'] = {widget: 'textarea', options: {autoSize: {minRows: 2, maxRows: 6}}};
    }
    if (properties.postLogoutRedirectUris) {
      properties.postLogoutRedirectUris['x-ui'] = {widget: 'textarea', options: {autoSize: {minRows: 2, maxRows: 6}}};
    }
    if (properties.scopes) {
      properties.scopes['x-ui'] = {widget: 'textarea', options: {autoSize: {minRows: 2, maxRows: 6}}};
    }

    addTypedField(properties, 'requireAuthorizationConsent', {
      type: 'boolean',
      title: t('clients.title.requireAuthorizationConsent', '需要授权确认'),
      description: t('clients.description.requireAuthorizationConsent', '启用后用户首次授权时需要确认权限范围'),
      default: true,
      'x-order': 20,
    });
    addTypedField(properties, 'requireProofKey', {
      type: 'boolean',
      title: t('clients.title.requireProofKey', '要求 PKCE'),
      description: t('clients.description.requireProofKey', 'authorization_code 客户端是否必须携带 code_challenge'),
      default: true,
      'x-order': 21,
    });
    addTypedField(properties, 'reuseRefreshTokens', {
      type: 'boolean',
      title: t('clients.title.reuseRefreshTokens', '复用刷新令牌'),
      description: t('clients.description.reuseRefreshTokens', '关闭后每次刷新都会轮换新的 refresh token'),
      default: true,
      'x-order': 22,
    });
    addTypedField(properties, 'accessTokenTtlMinutes', {
      type: 'integer',
      minimum: 1,
      title: t('clients.title.accessTokenTtlMinutes', '访问令牌有效期(分钟)'),
      description: t('clients.description.accessTokenTtlMinutes', 'access token 的有效时间'),
      default: 30,
      'x-order': 23,
    });
    addTypedField(properties, 'refreshTokenTtlHours', {
      type: 'integer',
      minimum: 1,
      title: t('clients.title.refreshTokenTtlHours', '刷新令牌有效期(小时)'),
      description: t('clients.description.refreshTokenTtlHours', 'refresh token 的有效时间'),
      default: 8,
      'x-order': 24,
    });
    addTypedField(properties, 'authorizationCodeTtlMinutes', {
      type: 'integer',
      minimum: 1,
      title: t('clients.title.authorizationCodeTtlMinutes', '授权码有效期(分钟)'),
      description: t('clients.description.authorizationCodeTtlMinutes', 'authorization code 的有效时间'),
      default: 5,
      'x-order': 25,
    });
    addTypedField(properties, 'deviceCodeTtlMinutes', {
      type: 'integer',
      minimum: 1,
      title: t('clients.title.deviceCodeTtlMinutes', '设备码有效期(分钟)'),
      description: t('clients.description.deviceCodeTtlMinutes', 'device code 的有效时间'),
      default: 5,
      'x-order': 26,
    });
    addTypedField(properties, 'accessTokenFormat', {
      type: 'string',
      title: t('clients.title.accessTokenFormat', '访问令牌格式'),
      description: t('clients.description.accessTokenFormat', 'self-contained 为 JWT，reference 为引用令牌'),
      oneOf: [
        {const: 'self-contained', title: 'self-contained'},
        {const: 'reference', title: 'reference'},
      ],
      default: 'self-contained',
      'x-order': 27,
    });
    addTypedField(properties, 'idTokenSignatureAlgorithm', {
      type: 'string',
      title: t('clients.title.idTokenSignatureAlgorithm', 'ID Token 签名算法'),
      oneOf: ['PS256', 'RS256', 'ES256'].map((value) => ({const: value, title: value})),
      default: 'PS256',
      'x-order': 28,
    });
    addTypedField(properties, 'tokenEndpointAuthenticationSigningAlgorithm', {
      type: 'string',
      title: t('clients.title.tokenEndpointAuthenticationSigningAlgorithm', 'Token Endpoint 签名算法'),
      oneOf: ['PS256', 'RS256', 'ES256'].map((value) => ({const: value, title: value})),
      default: 'PS256',
      'x-order': 29,
    });
    addTypedField(properties, 'jwkSetUrl', {
      type: 'string',
      title: t('clients.title.jwkSetUrl', 'JWK Set URL'),
      description: t('clients.description.jwkSetUrl', '客户端 JWT 认证使用的 JWK Set 地址，可选'),
      'x-order': 30,
    });
    addTypedField(properties, 'x509CertificateBoundAccessTokens', {
      type: 'boolean',
      title: t('clients.title.x509CertificateBoundAccessTokens', '绑定 X.509 证书'),
      description: t('clients.description.x509CertificateBoundAccessTokens', '访问令牌是否绑定客户端证书'),
      default: false,
      'x-order': 31,
    });

    return next;
  }, [addTypedField, t]);

  const formUiSchema = useMemo(() => ({
    clientSecret: {
      'ui:widget': 'password',
    },
    redirectUris: {
      'ui:widget': 'textarea',
      'ui:options': {autoSize: {minRows: 2, maxRows: 6}},
    },
    postLogoutRedirectUris: {
      'ui:widget': 'textarea',
      'ui:options': {autoSize: {minRows: 2, maxRows: 6}},
    },
    scopes: {
      'ui:widget': 'textarea',
      'ui:options': {autoSize: {minRows: 2, maxRows: 6}},
    },
    requireAuthorizationConsent: {'ui:widget': 'checkbox'},
    requireProofKey: {'ui:widget': 'checkbox'},
    reuseRefreshTokens: {'ui:widget': 'checkbox'},
    x509CertificateBoundAccessTokens: {'ui:widget': 'checkbox'},
    accessTokenFormat: {'ui:widget': 'select'},
    idTokenSignatureAlgorithm: {'ui:widget': 'select'},
    tokenEndpointAuthenticationSigningAlgorithm: {'ui:widget': 'select'},
  }), []);

  const customButtonEvents = useMemo<Record<string, (keys: React.Key[], rows: any[], props: TableButtonProps) => void>>(
    () => ({
      add: () => {
        setEditingRecord(null);
        setInitialValues({...defaultFormData});
        setDrawerOpen(true);
      },
      edit: async (_keys, rows) => {
        const id = rows?.[0]?.id;
        if (!id) return;
        try {
          const configuration = await get<OidcClientConfiguration>(`${baseConfig.baseUrl}/${id}/configuration`);
          const formData = configurationToForm(configuration);
          setEditingRecord(formData);
          setInitialValues(formData);
          setDrawerOpen(true);
        } catch (error) {
          message.error(t('table.actionFail', '操作失败: {msg}', {msg: resolveApiErrorMessage(error, '')}));
        }
      },
    }),
    [t]
  );

  const handleSubmit = useCallback(async (
    action: SimpleTableSubmitAction,
    formData: OidcClientFormData,
    currentEditing: OidcClientFormData | null
  ) => {
    const payload = formToConfiguration(formData, currentEditing);
    if (action === 'edit') {
      if (!currentEditing?.id) {
        throw new Error(t('clients.error.missingId', '缺少客户端记录ID'));
      }
      await put(`${baseConfig.baseUrl}/${currentEditing.id}/configuration`, payload);
      message.success(t('table.editSuccess', '修改成功'));
      return;
    }
    await post(`${baseConfig.baseUrl}/configuration`, payload);
    message.success(t('table.addSuccess', '新增成功'));
  }, [t]);

  const columnOverrides = useMemo(() => ({
    clientAuthenticationMethods: {
      render: renderCsvTags,
    },
    authorizationGrantTypes: {
      render: renderCsvTags,
    },
    scopes: {
      render: renderCsvTags,
    },
  }), []);

  return (
    <div>
      <SimpleTable
        {...baseConfig}
        drawerOpen={drawerOpen}
        onDrawerOpenChange={(open) => {
          setDrawerOpen(open);
          if (!open) {
            setEditingRecord(null);
            setInitialValues({...defaultFormData});
          }
        }}
        editingRecord={editingRecord}
        onEditingRecordChange={setEditingRecord}
        initialValues={initialValues}
        customButtonEvents={customButtonEvents}
        formSchemaTransform={formSchemaTransform}
        formUiSchema={formUiSchema}
        onSubmit={handleSubmit}
        columnOverrides={columnOverrides}
        i18nNamespaces={[...baseConfig.i18nNamespaces, 'table', 'common']}
      />
    </div>
  );
};

export default App;
