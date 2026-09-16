import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App as AntdApp, Descriptions, Tag} from 'antd';
import React, {useCallback, useEffect, useMemo, useState} from 'react';

const baseConfig = api['external-identity-providers'];

type IdentityProviderRow = {
  id: string;
  registrationId: string;
  displayName: string;
  preset: 'CUSTOM' | 'GOOGLE' | 'GITHUB' | 'APPLE' | 'MICROSOFT';
  protocol: 'OIDC' | 'OAUTH2';
  enabled: boolean;
  clientSecretConfigured?: boolean;
};

type ConnectionTestResult = {
  success: boolean;
  message: string;
  authorizationUri?: string;
  tokenUri?: string;
  userInfoUri?: string;
  jwkSetUri?: string;
  callbackUri?: string;
};

const IdentityProviderView = () => {
  const {message, modal} = AntdApp.useApp();
  const {ensure, locale, t} = useI18n();
  const [tableKey, setTableKey] = useState(0);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const formSchemaTransform = useCallback((schema: any, editingRecord?: IdentityProviderRow) => {
    const next = structuredClone(schema);
    const properties = next?.properties ?? {};

    delete properties.clientSecretConfigured;
    const optionalServerDefaultFields = new Set([
      'protocol',
      'issuerUri',
      'authorizationUri',
      'tokenUri',
      'userInfoUri',
      'jwkSetUri',
      'clientAuthenticationMethod',
      'scopes',
      'userNameAttribute',
      'subjectClaim',
      'emailClaim',
      'emailVerifiedClaim',
      'matchStrategy',
      'requireVerifiedEmail',
      'allowPrivateNetwork',
      'sortOrder',
      'enabled',
    ]);
    next.required = Array.isArray(next.required)
      ? next.required.filter((key: string) =>
        !optionalServerDefaultFields.has(key)
        && !(editingRecord && key === 'clientSecret'))
      : [];

    properties.preset &&= {
      ...properties.preset,
      default: 'CUSTOM',
    };
    properties.protocol &&= {
      ...properties.protocol,
      default: 'OIDC',
    };
    properties.clientAuthenticationMethod &&= {
      ...properties.clientAuthenticationMethod,
      default: 'client_secret_basic',
    };
    properties.scopes &&= {
      ...properties.scopes,
      default: 'openid,profile,email',
    };
    properties.userNameAttribute &&= {
      ...properties.userNameAttribute,
      default: 'sub',
    };
    properties.subjectClaim &&= {
      ...properties.subjectClaim,
      default: 'sub',
    };
    properties.emailClaim &&= {
      ...properties.emailClaim,
      default: 'email',
    };
    properties.emailVerifiedClaim &&= {
      ...properties.emailVerifiedClaim,
      default: 'email_verified',
    };
    properties.matchStrategy &&= {
      ...properties.matchStrategy,
      default: 'VERIFIED_EMAIL',
    };
    properties.requireVerifiedEmail &&= {
      ...properties.requireVerifiedEmail,
      default: true,
    };
    properties.allowPrivateNetwork &&= {
      ...properties.allowPrivateNetwork,
      default: false,
    };
    properties.sortOrder &&= {
      ...properties.sortOrder,
      default: 100,
      minimum: 0,
    };
    properties.enabled &&= {
      ...properties.enabled,
      default: true,
    };
    if (properties.clientSecret) {
      properties.clientSecret.description = t(
        'external-idp.page.form.secret.edit',
        '编辑时留空表示保持当前加密密钥不变'
      );
    }
    if (editingRecord && properties.registrationId) {
      properties.registrationId.readOnly = true;
    }
    return next;
  }, [t]);

  const handleTest = useCallback(async (rows: IdentityProviderRow[]) => {
    const provider = rows?.[0];
    if (!provider?.id) {
      return;
    }
    const hide = message.loading(t('external-idp.button.test', '正在测试配置…'), 0);
    try {
      const result = await post<ConnectionTestResult>(
        `${baseConfig.baseUrl}/${provider.id}/test`,
        {}
      );
      hide();
      modal.success({
        title: t('external-idp.page.test.success', '配置有效'),
        content: (
          <Descriptions column={1} size="small" style={{marginTop: 16}}>
            <Descriptions.Item label="Authorization URI">
              {result.authorizationUri || '--'}
            </Descriptions.Item>
            <Descriptions.Item label="Token URI">
              {result.tokenUri || '--'}
            </Descriptions.Item>
            <Descriptions.Item label="UserInfo URI">
              {result.userInfoUri || '--'}
            </Descriptions.Item>
            <Descriptions.Item label="JWK Set URI">
              {result.jwkSetUri || '--'}
            </Descriptions.Item>
            <Descriptions.Item label={t('external-idp.page.test.callback', '回调地址')}>
              {result.callbackUri || '--'}
            </Descriptions.Item>
          </Descriptions>
        ),
      });
      setTableKey((value) => value + 1);
    } catch (error) {
      hide();
      message.error(t(
        'external-idp.page.test.failed',
        '配置测试失败：{msg}',
        {msg: resolveApiErrorMessage(error, '')}
      ));
    }
  }, [message, modal, t]);

  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: IdentityProviderRow[],
    props: TableButtonProps,
  ) => void>>(() => ({
    test: (_keys, rows) => void handleTest(rows),
  }), [handleTest]);

  const columnOverrides = useMemo(() => ({
    clientSecretConfigured: {
      width: 110,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value ? t('common.yes', '是') : t('common.no', '否')}
        </Tag>
      ),
    },
    enabled: {width: 90},
    sortOrder: {width: 100},
  }), [t]);

  return (
    <div>
      <Alert
        type="info"
        closable
        showIcon
        style={{marginBottom: 16}}
        message={t(
          'external-idp.entity.title',
          '外部身份提供商'
        )}
        description={t(
          'external-idp.page.callback',
          '在外部平台登记回调地址：{url}',
          {url: 'https://授权服务域名/login/oauth2/code/{registrationId}'}
        )}
      />
      <Alert
        type="warning"
        showIcon
        style={{marginBottom: 16}}
        message={t('external-idp.page.github.title', 'GitHub 接入检查')}
        description={t(
          'external-idp.page.github.description',
          'GitHub OAuth App 必须填写与授权服务完全一致的 Authorization callback URL；授权范围保留 read:user,user:email。配置测试只校验端点与格式，Client ID/Secret 需要通过一次真实登录确认。'
        )}
      />
      <SimpleTable
        key={tableKey}
        {...baseConfig}
        customButtonEvents={customButtonEvents}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
      />
    </div>
  );
};

export default IdentityProviderView;
