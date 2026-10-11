import type {RJSFSchema} from '@rjsf/utils';
import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
import {MAX_APPS, MAX_GROUPS, type HomePreferences} from './preferences.ts';

export function homeSchema(t: TranslateFn, timeZone: string): RJSFSchema {
  const intl = Intl as typeof Intl & {supportedValuesOf?: (key: string) => string[]};
  const zones = [...new Set(['', 'UTC', Intl.DateTimeFormat().resolvedOptions().timeZone, ...(intl.supportedValuesOf?.('timeZone') ?? []), timeZone])];
  const timeZoneSchema: RJSFSchema = {type: 'string', title: t('home.timeZone', '时区'), enum: zones,
    'x-ui': {widget: 'select', options: {enumNames: {'': t('home.timeZoneAuto', '跟随设备时区')}}}};
  return {type: 'object', additionalProperties: false, required: ['version', 'groups', 'region', 'timeZone'], properties: {
    version: {type: 'integer', const: 1}, groups: {type: 'array', maxItems: MAX_GROUPS, title: t('home.apps', '常用应用'), items: {
      type: 'object', additionalProperties: false, required: ['id', 'name', 'paths'], properties: {
        id: {type: 'string', maxLength: 80, pattern: '\\S'},
        name: {type: 'string', maxLength: 40, pattern: '\\S', title: t('home.groupName', '分组名称'), default: t('home.newGroup', '新分组')},
        paths: {type: 'array', maxItems: MAX_APPS, uniqueItems: true, title: t('home.selectApps', '选择常用应用'), items: {
          type: 'string', maxLength: 512, pattern: '^/(?!/)[^\\s\\\\?#:*]*$',
        }},
      },
    }}, region: {type: 'string', maxLength: 60, title: t('home.region', '地区')},
    timeZone: timeZoneSchema,
  }};
}
/** Only new array items need an identity; existing identifiers are never rewritten on edits/reordering. */
export function homeDraft(value: HomePreferences, newId: () => string): HomePreferences {
  return {...value, groups: value.groups.map(group => ({...group, id: group.id || newId(), paths: group.paths ?? []}))};
}
export function appSelection(paths: string[], visible: string[], options: {value: string}[]): string[] {
  const available = new Set(options.map(option => option.value));
  return [...paths.filter(path => !available.has(path)), ...visible];
}
