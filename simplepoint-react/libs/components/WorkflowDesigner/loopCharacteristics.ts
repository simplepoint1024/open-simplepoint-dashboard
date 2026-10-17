/** Typed projection of editable BPMN loop properties; the moddle object remains authoritative. */
export type ActivityLoop = {
  $type: string;
  isSequential?: boolean;
  collection?: string;
  elementVariable?: string;
  elementIndexVariable?: string;
  loopCardinality?: {$type?: string; body?: string};
  completionCondition?: {$type?: string; body?: string};
  testBefore?: boolean;
  loopMaximum?: number;
  loopCondition?: {$type?: string; body?: string};
};
export type LoopOwner = {$parent?: LoopOwner; loopCharacteristics?: ActivityLoop};
export type ExecutionMode = 'none' | 'parallel' | 'sequential' | 'standard';
export const STANDARD_LOOP_TYPE = 'bpmn:StandardLoopCharacteristics';
export const loopConditionValue = (loop?: ActivityLoop) =>
  (loop?.$type === STANDARD_LOOP_TYPE ? loop.loopCondition?.body : loop?.completionCondition?.body) ?? '';
