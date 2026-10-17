import {canCreateParticipants, modelingCapabilities} from './modelingCapabilities.ts';

type Entry = {group?: string; className?: string; title?: string;
  action?: Record<string, (event: unknown, element?: unknown) => unknown>};
type Entries = Record<string, Entry>;
type ProviderRegistry = {registerProvider: (...arguments_: unknown[]) => void};
type Factory = {createShape: (attributes: {type: string}) => unknown};
type Create = {start: (event: unknown, shape: unknown, options?: {source: unknown}) => void};
type AutoPlace = {append: (source: unknown, shape: unknown) => void};

const replacements = new Set(modelingCapabilities.replacementEntries);
type Shape = {id?: string; type?: string};
type CreationEvent = {context: {shape?: Shape; shapes?: Shape[]; elements?: Shape[]}};

/** Use bpmn-js provider hooks, not CSS hiding or monkey-patching. Imported legacy diagrams
 * remain readable/editable; new task creation always creates an actual human UserTask. */
export class PlatformModelingProvider {
  static $inject = ['palette', 'contextPad', 'popupMenu', 'create', 'elementFactory', 'injector', 'workflowDesignLabels', 'eventBus', 'elementRegistry'];
  create: Create;
  factory: Factory;
  autoPlace?: AutoPlace;
  labels: {userTask: string};

  constructor(palette: ProviderRegistry, contextPad: ProviderRegistry, popupMenu: ProviderRegistry,
    create: Create, factory: Factory, injector: {get: (name: string, strict: boolean) => AutoPlace | undefined},
    labels: {userTask: string},
    eventBus: {on: (event: string[], priority: number, callback: (event: CreationEvent) => false | undefined) => void},
    elementRegistry: {getAll: () => Shape[]}) {
    this.create = create; this.factory = factory; this.autoPlace = injector.get('autoPlace', false); this.labels = labels;
    // Lower priority applies our updater after the built-in providers have supplied entries.
    palette.registerProvider(500, this);
    contextPad.registerProvider(500, this);
    popupMenu.registerProvider('bpmn-replace', 500, this);
    // Keep the real pool tool. Rules protect drag, paste and multi-shape creation before commands run.
    eventBus.on(['commandStack.shape.create.canExecute', 'commandStack.elements.create.canExecute'], 2000, event => {
      const {shape, shapes, elements} = event.context;
      if (!canCreateParticipants(elementRegistry.getAll(), elements ?? shapes ?? (shape ? [shape] : []))) return false;
    });
  }

  userTask(source?: unknown): Entry {
    const start = (event: unknown) => this.create.start(event,
      this.factory.createShape({type: 'bpmn:UserTask'}), source ? {source} : undefined);
    return {group: source ? 'model' : 'activity', className: 'bpmn-icon-user-task', title: this.labels.userTask,
      action: {dragstart: start, click: source && this.autoPlace
        ? () => this.autoPlace!.append(source, this.factory.createShape({type: 'bpmn:UserTask'})) : start}};
  }

  getPaletteEntries() {
    return (entries: Entries): Entries => {
      const next = {...entries};
      delete next['create.task'];
      delete next['create.intermediate-event'];
      next['create.user-task'] = this.userTask();
      return next;
    };
  }

  getContextPadEntries(element: unknown) {
    return (entries: Entries): Entries => {
      const next = {...entries};
      for (const key of Object.keys(next)) {
        if (key.startsWith('append.') && /(?:intermediate-event|receive-task|event-based-gateway)/.test(key)) delete next[key];
      }
      if (next['append.append-task']) {
        delete next['append.append-task']; next['append.user-task'] = this.userTask(element);
      }
      return next;
    };
  }

  getPopupMenuEntries() {
    return (entries: Entries): Entries => Object.fromEntries(Object.entries(entries)
      .filter(([key]) => !key.startsWith('replace-with-') || replacements.has(key)));
  }

  getPopupMenuHeaderEntries() {
    return (entries: Entries): Entries => Object.fromEntries(Object.entries(entries)
      .filter(([key]) => key !== 'toggle-participant-multiplicity'));
  }
}

export default {__init__: ['platformModelingProvider'], platformModelingProvider: ['type', PlatformModelingProvider]};
