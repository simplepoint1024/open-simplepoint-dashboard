import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  Avatar,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Row,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  EditOutlined,
  IdcardOutlined,
  LockOutlined,
  ReloadOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
  UserOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import {useQueryClient} from '@tanstack/react-query';
import {OssImageUpload} from '@simplepoint/components/SForm/widgets/OssImageUpload';
import {post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@/layouts/i18n/useI18n.ts';
import {type CurrentProfile, useCurrentProfile, useUserInfo} from '@/fetches/user.ts';
import './index.css';

type ProfileFormValues = {
  nickname?: string;
  email?: string;
  phoneNumber?: string;
  picture?: string;
  familyName?: string;
  givenName?: string;
  middleName?: string;
  gender?: string;
  birthdate?: dayjs.Dayjs | null;
  address?: string;
  profile?: string;
  website?: string;
  locale?: string;
  zoneinfo?: string;
};

const toFormValues = (profile: CurrentProfile): ProfileFormValues => ({
  nickname: profile.nickname || '',
  email: profile.email || '',
  phoneNumber: profile.phoneNumber || '',
  picture: profile.picture || undefined,
  familyName: profile.familyName || '',
  givenName: profile.givenName || '',
  middleName: profile.middleName || '',
  gender: profile.gender || undefined,
  birthdate: profile.birthdate ? dayjs(profile.birthdate) : null,
  address: profile.address || '',
  profile: profile.profile || '',
  website: profile.website || '',
  locale: profile.locale || undefined,
  zoneinfo: profile.zoneinfo || undefined,
});

const toUpdatePayload = (values: ProfileFormValues) => ({
  ...values,
  birthdate: values.birthdate?.isValid() ? values.birthdate.toISOString() : null,
});

export const Profile: React.FC = () => {
  const {t, ensure, locale} = useI18n();
  const queryClient = useQueryClient();
  const {data, isLoading, refetch} = useCurrentProfile();
  const {data: userInfo, refetch: refetchUserInfo} = useUserInfo();
  const [detailOpen, setDetailOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [avatarSaving, setAvatarSaving] = useState(false);
  const [detailForm] = Form.useForm<ProfileFormValues>();
  const detailPicture = Form.useWatch('picture', detailForm);

  useEffect(() => {
    void ensure(['profile']);
  }, [ensure, locale]);

  const syncProfile = useCallback((updated: CurrentProfile) => {
    queryClient.setQueryData(['current-profile'], updated);
    queryClient.setQueryData(['userinfo'], (current: any) => ({
      ...(current || {}),
      ...updated,
      phone: updated.phoneNumber ?? current?.phone,
    }));
    window.dispatchEvent(new CustomEvent('sp-user-profile-updated', {detail: updated}));
  }, [queryClient]);

  useEffect(() => {
    if (detailOpen && data) {
      detailForm.setFieldsValue(toFormValues(data));
    }
  }, [data, detailForm, detailOpen]);

  useEffect(() => {
    const handleFocus = () => {
      void refetch();
      void refetchUserInfo();
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') handleFocus();
    };
    window.addEventListener('focus', handleFocus);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [refetch, refetchUserInfo]);

  const roles = useMemo(() => {
    const raw = userInfo?.roles;
    if (!Array.isArray(raw)) return [];
    return raw.map((role: any) => typeof role === 'string' ? role : role?.name).filter(Boolean) as string[];
  }, [userInfo]);

  const displayName = data?.nickname || data?.name || t('user.defaultName', '用户');

  const saveDetails = async () => {
    try {
      const values = await detailForm.validateFields();
      setSaving(true);
      const updated = await put<CurrentProfile>('/common/users/me', toUpdatePayload(values));
      syncProfile(updated);
      setDetailOpen(false);
      message.success(t('profile.saveSuccess', '个人资料已保存'));
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message ?? t('profile.saveFailed', '个人资料保存失败'));
    } finally {
      setSaving(false);
    }
  };

  const saveAvatar = async (picture?: string) => {
    if (!data) return;
    setAvatarSaving(true);
    try {
      const updated = await put<CurrentProfile>('/common/users/me', {
        ...toUpdatePayload(toFormValues(data)),
        picture: picture || null,
      });
      syncProfile(updated);
      message.success(t('profile.avatarSaved', '头像已更新'));
    } finally {
      setAvatarSaving(false);
    }
  };

  const [pwdSaving, setPwdSaving] = useState(false);
  const [pwdForm] = Form.useForm();
  const onChangePassword = async () => {
    try {
      const values = await pwdForm.validateFields();
      if (values.newPassword !== values.confirmPassword) {
        pwdForm.setFields([{name: 'confirmPassword', errors: [t('rule.passwordMismatch', '两次输入的密码不一致')]}]);
        return;
      }
      setPwdSaving(true);
      await post('/common/users/change-password', values);
      message.success(t('profile.passwordChanged', '密码修改成功'));
      pwdForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message ?? t('profile.passwordChangeFailed', '密码修改失败'));
    } finally {
      setPwdSaving(false);
    }
  };

  const openTwoFactorSettings = () => {
    const path = '/authorization/account/2fa?gateway=true';
    const opened = window.open(path, '_blank', 'noopener,noreferrer');
    if (!opened) window.location.assign(path);
  };

  const formatDate = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';

  return (
    <div className="profile-page">
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card className="profile-card profile-identity-card">
            {isLoading ? <Skeleton avatar active paragraph={{rows: 3}}/> : data ? (
              <div className="avatar-card">
                <div className="profile-avatar-ring">
                  <Avatar
                    size={88}
                    src={data.picture || undefined}
                    icon={!data.picture ? <UserOutlined/> : undefined}
                    alt={displayName}
                  />
                </div>
                <Typography.Title className="profile-name" level={4}>{displayName}</Typography.Title>
                <Typography.Paragraph className="profile-sub">
                  {data.email || t('profile.noEmail', '未绑定邮箱')}
                </Typography.Paragraph>
                <OssImageUpload
                  value={data.picture}
                  onChange={(picture) => void saveAvatar(picture)}
                  disabled={avatarSaving}
                  directory="avatars/users"
                  sourceServiceName="rbac-avatar"
                  shape="circle"
                  maxSizeMb={5}
                />
              </div>
            ) : <Empty/>}
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Card
            className="profile-card"
            title={<span><IdcardOutlined/> {t('profile.basic', '基本信息')}</span>}
            extra={data ? (
              <Space>
                <Button icon={<ReloadOutlined/>} onClick={() => void refetch()}>
                  {t('action.refresh', '刷新')}
                </Button>
                <Button type="primary" icon={<EditOutlined/>} onClick={() => setDetailOpen(true)}>
                  {t('profile.editDetails', '编辑详细资料')}
                </Button>
              </Space>
            ) : undefined}
          >
            {isLoading ? <Skeleton active paragraph={{rows: 7}}/> : !data ? <Empty/> : (
              <Descriptions column={{xs: 1, sm: 2}} size="middle" className="profile-descriptions">
                <Descriptions.Item label={t('field.nickname', '昵称')}>{data.nickname || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('field.fullName', '姓名')}>
                  {[data.familyName, data.middleName, data.givenName].filter(Boolean).join(' ') || data.name || '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('field.email', '邮箱')}>{data.email || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('field.phone', '手机号')}>{data.phoneNumber || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('field.roles', '角色')} span={2}>
                  {roles.length ? roles.map((role) => <Tag key={role} color="blue">{role}</Tag>) : '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('field.profile', '个人简介')} span={2}>
                  {data.profile || '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('field.joinedAt', '加入时间')}>{formatDate(data.createdAt)}</Descriptions.Item>
                <Descriptions.Item label={t('field.updatedAt', '最近更新')}>{formatDate(data.updatedAt)}</Descriptions.Item>
              </Descriptions>
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={{span: 16, offset: 8}}>
          <Card className="profile-card profile-security-card">
            <div className="profile-security-row">
              <div className={`profile-security-icon ${data?.twoFactorEnabled ? 'enabled' : ''}`}>
                <SafetyCertificateOutlined/>
              </div>
              <div className="profile-security-copy">
                <Space size={8} wrap>
                  <Typography.Title level={5}>{t('field.twoFactorEnabled', '两步认证')}</Typography.Title>
                  <Tag color={data?.twoFactorEnabled ? 'success' : 'default'}>
                    {data?.twoFactorEnabled
                      ? t('profile.twoFactor.enabled', '已开启')
                      : t('profile.twoFactor.disabled', '未开启')}
                  </Tag>
                </Space>
                <Typography.Paragraph>
                  {t('profile.twoFactor.description', '使用动态验证码为账号增加一层登录保护。')}
                </Typography.Paragraph>
              </div>
              <Button type="link" icon={<RightOutlined/>} iconPosition="end" onClick={openTwoFactorSettings}>
                {data?.twoFactorEnabled
                  ? t('action.manageTwoFactor', '管理两步认证')
                  : t('action.enableTwoFactor', '开启两步认证')}
              </Button>
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={{span: 16, offset: 8}}>
          <Card
            className="profile-card"
            title={<span><LockOutlined/> {t('profile.changePassword', '修改密码')}</span>}
          >
            <Form layout="vertical" form={pwdForm} className="profile-password-form">
              <Form.Item
                name="currentPassword"
                label={t('field.currentPassword', '当前密码')}
                rules={[{required: true, message: t('rule.currentPassword', '请输入当前密码')}]}
              >
                <Input.Password maxLength={64} autoComplete="current-password"/>
              </Form.Item>
              <Row gutter={12}>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="newPassword"
                    label={t('field.newPassword', '新密码')}
                    rules={[{required: true, message: t('rule.newPassword', '请输入新密码')}, {min: 6, message: t('rule.passwordLength', '密码长度至少 6 位')}]}
                  >
                    <Input.Password maxLength={64} autoComplete="new-password"/>
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="confirmPassword"
                    label={t('field.confirmPassword', '确认新密码')}
                    rules={[{required: true, message: t('rule.confirmPassword', '请再次输入新密码')}]}
                  >
                    <Input.Password maxLength={64} autoComplete="new-password"/>
                  </Form.Item>
                </Col>
              </Row>
              <Button type="primary" icon={<LockOutlined/>} loading={pwdSaving} onClick={onChangePassword}>
                {t('action.changePassword', '修改密码')}
              </Button>
            </Form>
          </Card>
        </Col>
      </Row>

      <Drawer
        title={<Space><IdcardOutlined/>{t('profile.details.title', '编辑详细资料')}</Space>}
        width={680}
        open={detailOpen}
        onClose={() => !saving && setDetailOpen(false)}
        maskClosable={!saving}
        extra={(
          <Space>
            <Button disabled={saving} onClick={() => setDetailOpen(false)}>{t('action.cancel', '取消')}</Button>
            <Button type="primary" icon={<SaveOutlined/>} loading={saving} onClick={() => void saveDetails()}>
              {t('action.save', '保存')}
            </Button>
          </Space>
        )}
      >
        <Form layout="vertical" form={detailForm} className="profile-detail-form">
          <Divider titlePlacement="start">{t('profile.details.identity', '身份信息')}</Divider>
          <Form.Item label={t('users.title.picture', '头像')}>
            <OssImageUpload
              value={detailPicture}
              onChange={(picture) => detailForm.setFieldValue('picture', picture)}
              directory="avatars/users"
              sourceServiceName="rbac-avatar"
              shape="circle"
            />
          </Form.Item>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="nickname"
                label={t('field.nickname', '昵称')}
                rules={[{required: true, message: t('rule.nickname', '请输入昵称')}]}
              >
                <Input maxLength={50}/>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="gender" label={t('field.gender', '性别')}>
                <Select allowClear options={[
                  {value: 'male', label: t('gender.male', '男')},
                  {value: 'female', label: t('gender.female', '女')},
                  {value: 'other', label: t('gender.other', '其他')},
                ]}/>
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={24} sm={8}><Form.Item name="familyName" label={t('field.familyName', '姓氏')}><Input maxLength={50}/></Form.Item></Col>
            <Col xs={24} sm={8}><Form.Item name="middleName" label={t('field.middleName', '中间名')}><Input maxLength={50}/></Form.Item></Col>
            <Col xs={24} sm={8}><Form.Item name="givenName" label={t('field.givenName', '名字')}><Input maxLength={50}/></Form.Item></Col>
          </Row>
          <Form.Item name="birthdate" label={t('field.birthdate', '出生日期')}><DatePicker style={{width: '100%'}}/></Form.Item>

          <Divider titlePlacement="start">{t('profile.details.contact', '联系方式')}</Divider>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="email"
                label={t('field.email', '邮箱')}
                rules={[{type: 'email', message: t('rule.email', '邮箱格式不正确')}]}
              >
                <Input maxLength={64}/>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}><Form.Item name="phoneNumber" label={t('field.phone', '手机号')}><Input maxLength={18}/></Form.Item></Col>
          </Row>
          <Form.Item name="address" label={t('field.address', '联系地址')}><Input maxLength={255}/></Form.Item>
          <Form.Item name="website" label={t('field.website', '个人网站')} rules={[{type: 'url', warningOnly: true}]}><Input maxLength={255}/></Form.Item>

          <Divider titlePlacement="start">{t('profile.details.preferences', '个人偏好')}</Divider>
          <Form.Item name="profile" label={t('field.profile', '个人简介')}><Input.TextArea maxLength={500} showCount autoSize={{minRows: 3, maxRows: 6}}/></Form.Item>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item name="locale" label={t('field.locale', '语言区域')}>
                <Select allowClear options={[
                  {value: 'zh-CN', label: '简体中文 (zh-CN)'},
                  {value: 'en-US', label: 'English (en-US)'},
                ]}/>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="zoneinfo" label={t('field.zoneinfo', '时区')}>
                <Select showSearch allowClear options={[
                  {value: 'Asia/Shanghai', label: 'Asia/Shanghai'},
                  {value: 'Asia/Hong_Kong', label: 'Asia/Hong_Kong'},
                  {value: 'Asia/Tokyo', label: 'Asia/Tokyo'},
                  {value: 'Europe/London', label: 'Europe/London'},
                  {value: 'America/New_York', label: 'America/New_York'},
                ]}/>
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Drawer>
    </div>
  );
};
