import assert from 'node:assert/strict';
import test from 'node:test';
import {localizedServerMessage, selectApiErrorMessage, translatedServerMessage} from './errorMessage.ts';
import {createTranslator} from '../i18n/translator.ts';

test('generic envelope keys never hide actionable localized annotation validation details', () => {
  const translate = createTranslator({'form.validation.failed': 'Check the form', 'form.validation.minLength': 'Enter at least {limit} characters'});
  assert.equal(localizedServerMessage({code: 'VALIDATION_FAILED', messageKey: 'form.validation.failed', fieldErrors: [
    {field: 'name', keyword: 'minLength', params: {limit: 4, rejectedValue: 'private'}}]}, translate), 'Enter at least 4 characters');
});

test('server localization keys use only known bounded keys and never interpolate untrusted parameters', () => {
  const translate = (key, fallback) => key === 'workflow.modeling.multiCount' ? 'A bounded whole number is required' : fallback;
  assert.equal(translatedServerMessage({messageKey: 'workflow.modeling.multiCount', parameters: {unsafe: '<script>'}}, translate),
    'A bounded whole number is required');
  for (const data of [null, [], 'text', {}, {messageKey: 'unknown.key'}, {messageKey: '<script>'}, {messageKey: 'a'.repeat(201)}]) {
    assert.equal(translatedServerMessage(data, translate), undefined);
  }
  assert.equal(translatedServerMessage({messageKey: 'unknown.key'}, key => key), undefined);
});

test('structured error codes use the caller localized fallback', () => {
  assert.equal(selectApiErrorMessage({
    errorCode: 'AI_MODEL_REQUEST_INVALID',
    userMessage: '请求参数不正确',
    fallback: '模型配置无效，请检查后重试',
    errorMessage: 'HTTP 400 Bad Request',
  }), '模型配置无效，请检查后重试');
});

test('explicit server user messages remain available', () => {
  assert.equal(selectApiErrorMessage({
    serverMessage: 'A useful conflict',
    errorCode: 'CONFLICT',
    userMessage: 'A useful conflict',
    fallback: 'Fallback',
    errorMessage: 'HTTP 409 Conflict',
  }), 'A useful conflict');
});

test('machine codes in legacy message fields never become visible text', () => {
  assert.equal(selectApiErrorMessage({
    serverMessage: 'MCP_PROFILE_REVISION_CONFLICT',
    errorCode: 'MCP_PROFILE_REVISION_CONFLICT',
    userMessage: '数据已被修改，请刷新后重试',
    fallback: 'Draft 保存失败，请刷新后重试',
    errorMessage: 'HTTP 409 Conflict',
  }), 'Draft 保存失败，请刷新后重试');
});

test('generic HTTP errors keep their localized status message without a fallback', () => {
  assert.equal(selectApiErrorMessage({
    userMessage: '服务暂时不可用',
    errorMessage: 'HTTP 503 Service Unavailable',
  }), '服务暂时不可用');
});
