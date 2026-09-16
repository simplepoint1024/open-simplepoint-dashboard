export type JsonObject = Record<string, unknown>;

export type SchemaProperty = {
  id: string;
  name: string;
  type: string;
  description: string;
  required: boolean;
  source: JsonObject;
};

const isObject = (value: unknown): value is JsonObject => Boolean(value)
  && typeof value === 'object'
  && !Array.isArray(value);

export const parseJsonObjectSchema = (value?: string): JsonObject | undefined => {
  if (!value?.trim()) return undefined;
  try {
    const decoded: unknown = JSON.parse(value);
    return isObject(decoded) ? decoded : undefined;
  } catch {
    return undefined;
  }
};

export const schemaProperties = (schema?: JsonObject): SchemaProperty[] => {
  if (!schema || !isObject(schema.properties)) return [];
  const required = new Set(Array.isArray(schema.required)
    ? schema.required.map(String) : []);
  return Object.entries(schema.properties).map(([name, raw], index) => {
    const source = isObject(raw) ? raw : {type: 'string'};
    return {
      id: `${index}-${name}`,
      name,
      type: typeof source.type === 'string' ? source.type : 'string',
      description: typeof source.description === 'string' ? source.description : '',
      required: required.has(name),
      source,
    };
  });
};

export const hasInvalidSchemaProperties = (properties: SchemaProperty[]) => {
  const names = properties.map((property) => property.name.trim());
  return names.some((name) => !name) || new Set(names).size !== names.length;
};

export const buildJsonObjectSchema = (
  original: JsonObject | undefined,
  properties: SchemaProperty[],
  additionalProperties: boolean,
) => {
  const next: JsonObject = {
    ...(original ?? {}),
    type: 'object',
    properties: Object.fromEntries(properties
      .filter((property) => property.name.trim())
      .map((property) => [property.name.trim(), {
        ...property.source,
        type: property.type,
        ...(property.description.trim()
          ? {description: property.description.trim()}
          : {description: undefined}),
      }])),
    required: properties
      .filter((property) => property.required && property.name.trim())
      .map((property) => property.name.trim()),
    additionalProperties,
  };
  if ((next.required as string[]).length === 0) delete next.required;
  return JSON.stringify(next, null, 2);
};
