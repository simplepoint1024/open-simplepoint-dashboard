import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parseDueDuration, dueDuration, nonTemporalDueDateLiteral, ordinaryDueDate} from './dueDate.ts';

const literals = JSON.parse(readFileSync(new URL('./dueDateLiterals.fixture.json', import.meta.url), 'utf8'));

test('due-date scalar checks match the real task engine fixtures without resolving variables or calendar text', () => {
  for (const item of literals) assert.equal(nonTemporalDueDateLiteral(item.source), Boolean(item.typeIssue), item.source);
  for (const source of ['  ${ false }  ', '${ -0.5 }', '${ 1E+2 }']) assert.equal(nonTemporalDueDateLiteral(source), true);
  for (const source of ['', 'true', '100', '${"true"}', '${"100"}', '${approved ? due : null}',
    '${due.getClass()}', '${true}${false}', '${"P1D" + duration}']) {
    assert.equal(nonTemporalDueDateLiteral(source), false, source);
  }
});

test('ordinary absolute dates never use rollover, partial dates, or an inferred browser timezone', () => {
  for (const source of ['2026-10-12T12:00:00Z', '2024-02-29T00:00:00Z', '2000-02-29T23:59:59.123456789Z',
    '2026-10-12T12:00:00+08:00', '2026-10-12T12:00:00-05:30', '2026-10-12T12:00:00+18:00']) {
    assert.equal(ordinaryDueDate(source), true, source);
  }
  for (const source of ['2026-02-30T12:00:00Z', '2026-02-29T12:00:00Z', '1900-02-29T12:00:00Z',
    '2026-04-31T12:00:00Z', '2026-00-01T12:00:00Z', '2026-13-01T12:00:00Z', '2026-10-00T12:00:00Z',
    '2026-10-12T24:00:00Z', '2026-10-12T12:60:00Z', '2026-10-12T12:00:60Z', '2026-10-12T12:00:00+18:01',
    '2026-10-12T12:00:00+19:00', '2026-10-12T12:00:00+08:60', '2026-10-12T12:00:00', '2026-10-12T12:00Z',
    '2026-10-12', '100', 'P1D', '${due}']) assert.equal(ordinaryDueDate(source), false, source);
});

test('existing ISO durations round-trip through the ordinary due-date editor', () => {
  for (const value of ['P1D', 'PT2H', 'PT30M', 'P3DT4H5M6S', 'PT0S']) {
    assert.equal(dueDuration(parseDueDuration(value)), value);
  }
});

test('unsupported or malformed durations are not guessed or silently replaced', () => {
  for (const value of ['P', 'PT', 'P1DT', 'P1W', 'P1M', 'PT0.5H', 'P9999999999999999D', '${days}', '2026-10-09']) {
    assert.equal(parseDueDuration(value), undefined);
  }
  assert.throws(() => dueDuration({days: -1, hours: 0, minutes: 0, seconds: 0}));
});
