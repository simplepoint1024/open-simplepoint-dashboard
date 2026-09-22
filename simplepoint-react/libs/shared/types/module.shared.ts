// Keep shared dependencies asynchronous so remote entries only contain the loader.
// The host starts through an asynchronous bootstrap boundary.
export default {
  react: {
    singleton: true,
    eager: false,
    requiredVersion: '^19.0.0',
    strictVersion: false,
  },
  'react/jsx-runtime': {
    singleton: true,
    eager: false,
    requiredVersion: '^19.0.0',
    strictVersion: false,
  },
  'react/jsx-dev-runtime': {
    singleton: true,
    eager: false,
    requiredVersion: '^19.0.0',
    strictVersion: false,
  },
  'react-dom': {
    singleton: true,
    eager: false,
    requiredVersion: '^19.0.0',
    strictVersion: false,
  },
  'antd': {
    singleton: true,
    eager: false,
  },
  'dayjs': {
    singleton: true,
    eager: false,
  },
  'react-router': {
    singleton: true,
    eager: false,
  },
  '@simplepoint/shared/types/icon': {singleton: true, eager: false, requiredVersion: false},
  '@simplepoint/shared/loading/context': {singleton: true, eager: false, requiredVersion: false},
  '@simplepoint/shared/page/context': {singleton: true, eager: false, requiredVersion: false},
  // The feedback bridge owns process-wide UI state, so host and remotes must
  // resolve this exact subpath to one Module Federation instance.
  '@simplepoint/shared/api/feedbackBridge': {
    singleton: true,
    eager: false,
    requiredVersion: false,
  },
  '@tanstack/react-query': {
    singleton: true,
    eager: false,
  }
}
