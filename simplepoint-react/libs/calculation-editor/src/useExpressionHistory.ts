import {useState} from 'react';
import {changeExpression, expressionHistory, reconcileHistory, traverseHistory} from './history.ts';

/** A controlled host can reject or replace an edit; history never overwrites that decision. */
export function useExpressionHistory(value: string, onChange: (value: string) => void) {
  const [stored, setStored] = useState(() => expressionHistory(value));
  const current = reconcileHistory(stored, value);
  if (current !== stored) setStored(current);
  const accepted = current.present === value;
  return {
    canUndo: accepted && current.past.length > 0,
    canRedo: accepted && current.future.length > 0,
    change(next: string) {
      setStored(previous => changeExpression(previous, next, value));
      onChange(next);
    },
    traverse(direction: 'undo' | 'redo') {
      if (!accepted) return;
      const next = traverseHistory(current, direction);
      if (next === current) return;
      setStored(next); onChange(next.present);
    },
  };
}
