import assert from 'node:assert/strict';
import test from 'node:test';
import {priorityBooleanLiteral} from './priority.ts';

test('only boolean constants fail the immediate priority type check', () => {
  for (const source of ['${true}', '${false}', '  ${ false }  ']) assert.equal(priorityBooleanLiteral(source), true);
  for (const source of ['${0.5}', '${9007199254740993}', '${"true"}', '${"１２"}', '${null}',
    '${initiator == "actor" ? 10 : 20}', '${priority}', 'true', '10', '']) {
    assert.equal(priorityBooleanLiteral(source), false, source);
  }
});
