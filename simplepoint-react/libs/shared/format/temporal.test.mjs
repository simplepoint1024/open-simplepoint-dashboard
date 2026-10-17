import test from 'node:test';
import assert from 'node:assert/strict';
import {formatTemporal} from './temporal.ts';
test('date and civil datetime retain their calendar values in every viewer zone', () => {
  for (const timeZone of ['America/Los_Angeles','Asia/Tokyo']) {
    assert.equal(formatTemporal('2026-01-01',{locale:'en-US',timeZone}),'01/01/2026');
    assert.equal(formatTemporal('2026-01-01T00:30:00',{locale:'en-US',timeZone}),'01/01/2026, 00:30:00');
  }
});
test('instants convert zones while locale controls presentation', () => {
  assert.equal(formatTemporal('2026-01-01T00:30:00Z',{locale:'en-US',timeZone:'America/Los_Angeles'}),'12/31/2025, 16:30:00');
  assert.equal(formatTemporal('2026-01-01T00:30:00Z',{locale:'zh-CN',timeZone:'Asia/Shanghai'}),'2026/01/01 08:30:00');
});
test('invalid and masked values remain visible without being interpreted as dates', () => {
  for(const value of ['***','2026-02-30','not-a-date','01/02/2026']) assert.equal(formatTemporal(value),value);
  assert.equal(formatTemporal(null),'—'); assert.equal(formatTemporal('09:30',{kind:'time'}),'09:30');
});
