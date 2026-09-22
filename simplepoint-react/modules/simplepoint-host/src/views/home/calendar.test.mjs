import assert from 'node:assert/strict';
import test from 'node:test';
import {addDays, addMonths, dayDate, monthDays, zonedDay} from './calendar.ts';

test('today follows the home time zone across date and year boundaries', () => {
  const now = new Date('2026-01-01T00:30:00Z');
  assert.equal(zonedDay(now, 'Asia/Shanghai'), '2026-01-01');
  assert.equal(zonedDay(now, 'America/Los_Angeles'), '2025-12-31');
  assert.equal(zonedDay(new Date('2026-09-18T12:30:00Z'), 'Pacific/Kiritimati'), '2026-09-19');
});
test('month navigation clamps leap days and crosses years', () => {
  assert.equal(addMonths('2024-01-31', 1), '2024-02-29');
  assert.equal(addMonths('2025-01-31', 1), '2025-02-28');
  assert.equal(addMonths('2026-12-15', 1), '2027-01-15');
  assert.equal(addMonths('2026-01-15', -1), '2025-12-15');
});
test('six-row Monday-first grid has no duplicates or local DST jumps', () => {
  for (const month of ['2024-02-01', '2026-03-01', '2026-11-01', '2026-12-01']) {
    const days = monthDays(month);
    assert.equal(days.length, 42);
    assert.equal(new Set(days).size, 42);
    assert.equal(dayDate(days[0]).getUTCDay(), 1);
    days.slice(1).forEach((day, index) => assert.equal(day, addDays(days[index], 1)));
    assert.ok(days.includes(month));
  }
  assert.ok(monthDays('2024-02-01').includes('2024-02-29'));
  assert.equal(addDays('2026-03-08', 1), '2026-03-09');
});
