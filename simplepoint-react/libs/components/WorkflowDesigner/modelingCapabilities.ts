// One versioned contract, packaged by the server API and consumed directly by the frontend build.
// Do not maintain a second menu/limit catalogue in the browser.
import contract from '../../../../simplepoint-plugins/simplepoint-plugin-workflow/simplepoint-plugin-workflow-api/src/main/resources/META-INF/simplepoint/workflow/modeling.json' with {type: 'json'};

export const modelingCapabilities = contract;
export const diagramTypes = new Set(contract.diagramTypes.map(type => `bpmn:${type}`));

export type DiagramBusinessObject = {
  $type?: string; name?: string; text?: string; textFormat?: string;
  categoryValueRef?: {value?: string}; dataObjectRef?: {isCollection?: boolean};
  participantMultiplicity?: unknown;
};

export function diagramLabel(business: DiagramBusinessObject): string {
  if (business.$type === 'bpmn:TextAnnotation') return business.text ?? '';
  if (business.$type === 'bpmn:Group') return business.categoryValueRef?.value ?? '';
  return business.name ?? '';
}

/** Creating the first pool wraps the existing process. Additional pools would create new processes. */
export function canCreateParticipants(existing: readonly {id?: string; type?: string}[], incoming: readonly {id?: string; type?: string}[]) {
  const pools = existing.filter(element => element.type === 'bpmn:Participant');
  const added = incoming.filter(element => element.type === 'bpmn:Participant' && !pools.includes(element));
  return !added.length || pools.length + added.length <= contract.maxParticipants;
}
