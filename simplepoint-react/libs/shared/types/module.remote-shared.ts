import hostShared from './module.shared';

const hostProvidedShared = new Set([
  'react',
  'react/jsx-runtime',
  'react/jsx-dev-runtime',
  'react-dom',
  'antd',
  'dayjs',
  'react-router',
  '@tanstack/react-query',
  '@simplepoint/shared/types/icon',
  '@simplepoint/shared/loading/context',
  '@simplepoint/shared/page/context',
  '@simplepoint/shared/api/feedbackBridge',
]);

// `import: false` is only safe when the host actually imports and registers the
// dependency. The shared list therefore contains only host-provided framework
// singletons and process-wide internal state. UI components and leaf libraries
// (RJSF, react-highlight-words) stay local to each remote so they can be split at
// their real usage boundary without creating missing-provider failures.
export default Object.fromEntries(
  Object.entries(hostShared).map(([name, config]) => [
    name,
    hostProvidedShared.has(name) ? {...config, import: false} : config,
  ]),
);
