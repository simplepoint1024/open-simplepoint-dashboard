import dayjs from 'dayjs';
import type {RJSFSchema} from '@rjsf/utils';
import type {CurrentProfile} from '@/fetches/user';

export const profileFields = ['nickname', 'gender', 'familyName', 'middleName', 'givenName', 'birthdate',
  'email', 'phoneNumber', 'address', 'website', 'profile', 'locale', 'zoneinfo'] as const;
export type ProfileDraft = Partial<Record<typeof profileFields[number], string>>;
export type PasswordDraft = {currentPassword?: string; newPassword?: string; confirmPassword?: string};
type Translate = (key: string, fallback?: string) => string;

const calendarDate = (value?: string | null) => value && dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD') : '';
const nullableText = (value?: string | null) => value?.trim() || null;
export function profileDraft(profile: CurrentProfile): ProfileDraft {
  return Object.fromEntries(profileFields.map(key => [key, key === 'birthdate' ? calendarDate(profile.birthdate) || undefined
    : key === 'email' ? profile.email || undefined : profile[key] || '']));
}
export function profilePayload(draft: ProfileDraft, original: CurrentProfile) {
  const values = Object.fromEntries(profileFields.map(key => [key, nullableText(draft[key])]));
  // Calendar dates are not instants: keep the original instant when the picker is untouched.
  values.birthdate = draft.birthdate === calendarDate(original.birthdate) ? original.birthdate ?? null
    : draft.birthdate ? dayjs(draft.birthdate).toISOString() : null;
  return {...values, picture: original.picture ?? null};
}
export function profileSchema(t: Translate, draft: ProfileDraft, languages: {code: string; name: string}[]): RJSFSchema {
  const text = (title: string, length: number) => ({type: 'string' as const, title, maxLength: length});
  const choices = (title: string, options: {const: string; title: string}[], current?: string) => {
    const values = [{const: '', title: t('profile.preferenceUnset', '未设置')}, ...options,
      ...(current && !options.some(option => option.const === current) ? [{const: current, title: current}] : [])];
    // A timezone is one enum, not hundreds of alternative schemas compiled on every validation.
    return {type: 'string' as const, title, enum: values.map(option => option.const),
      'x-ui': {widget: 'select', options: {enumNames: Object.fromEntries(values.map(option => [option.const, option.title]))}}};
  };
  let timezones: string[];
  try {timezones = (Intl as typeof Intl & {supportedValuesOf: (key: string) => string[]}).supportedValuesOf('timeZone');}
  catch {timezones = [Intl.DateTimeFormat().resolvedOptions().timeZone];}
  return {type: 'object', additionalProperties: false, required: ['nickname'], properties: {
    nickname: {...text(t('field.nickname', '昵称'), 50), minLength: 1, pattern: '\\S'},
    gender: choices(t('field.gender', '性别'), [{const: 'male', title: t('gender.male', '男')},
      {const: 'female', title: t('gender.female', '女')}, {const: 'other', title: t('gender.other', '其他')}], draft.gender),
    familyName: text(t('field.familyName', '姓氏'), 50), middleName: text(t('field.middleName', '中间名'), 50),
    givenName: text(t('field.givenName', '名字'), 50),
    birthdate: {type: 'string', title: t('field.birthdate', '出生日期'), format: 'date'},
    email: {...text(t('field.email', '邮箱'), 64), format: 'email'},
    phoneNumber: text(t('field.phone', '手机号'), 18), address: text(t('field.address', '联系地址'), 255),
    website: text(t('field.website', '个人网站'), 255), profile: text(t('field.profile', '个人简介'), 500),
    locale: choices(t('field.locale', '语言区域'), languages.map(language => ({const: language.code, title: language.name})), draft.locale),
    zoneinfo: choices(t('field.zoneinfo', '时区'), timezones.map(zone => ({const: zone, title: zone})), draft.zoneinfo),
  }};
}
export function passwordSchema(t: Translate): RJSFSchema {
  return {type: 'object', additionalProperties: false, required: ['currentPassword', 'newPassword', 'confirmPassword'], properties: {
    currentPassword: {type: 'string', title: t('field.currentPassword', '当前密码'), minLength: 1, maxLength: 64},
    newPassword: {type: 'string', title: t('field.newPassword', '新密码'), minLength: 6, maxLength: 64},
    confirmPassword: {type: 'string', title: t('field.confirmPassword', '确认新密码'), minLength: 1, maxLength: 64},
  }};
}
