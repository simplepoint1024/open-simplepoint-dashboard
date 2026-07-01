// src/utils/parseComponent.ts

type ParsedComponent =
  | { type: 'iframe'; payload: string }
  | { type: 'external'; payload: string }
  | { type: 'remote'; payload: string | undefined };

export function parseComponent(component: string | undefined): ParsedComponent {
    if (typeof component !== 'string') {
        return {type: 'remote', payload: component};
    }

    if (component.startsWith('iframe:')) {
        return {type: 'iframe', payload: component.slice('iframe:'.length)};
    }

    if (component.startsWith('external:')) {
        return {type: 'external', payload: component.slice('external:'.length)};
    }

    return {type: 'remote', payload: component};
}
