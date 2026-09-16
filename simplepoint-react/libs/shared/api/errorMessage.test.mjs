import assert from 'node:assert/strict';
import test from 'node:test';
import {selectApiErrorMessage} from './errorMessage.ts';

test('structured error codes use the caller localized fallback', () => {
  assert.equal(selectApiErrorMessage({
    errorCode: 'AI_SKILL_REQUEST_INVALID',
    userMessage: '请求参数不正确',
    fallback: 'Skill 配置无效，请检查后重试',
    errorMessage: 'HTTP 400 Bad Request',
  }), 'Skill 配置无效，请检查后重试');
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
    serverMessage: 'SKILL_DRAFT_REVISION_CONFLICT',
    errorCode: 'SKILL_DRAFT_REVISION_CONFLICT',
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
