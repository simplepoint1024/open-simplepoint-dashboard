export default {
  react: {
    singleton: true,
    requiredVersion: '^19.0.0',
    strictVersion: false,
    eager: true,
  },
  'react-dom': {
    singleton: true,
    requiredVersion: '^19.0.0',
    strictVersion: false,
    eager: true,
  },
  'antd': {
    singleton: true,
    eager: true,
  },
  'dayjs': {
    singleton: true,
    eager: true,
  },
  '@ant-design/icons':{
    singleton: true,
    requiredVersion: '^6.0.0',
    strictVersion: false,
    eager: true,
  },
  'react-router': {
    singleton: true,
    eager: true,
  },
  'react-highlight-words': {
    singleton: true,
    eager: true,
  },
  '@rjsf/antd': {
    singleton: true,
    eager: true,
  },
  '@rjsf/core': {
    singleton: true,
    eager: true,
  },
  '@rjsf/utils': {
    singleton: true,
    eager: true,
  },
  '@rjsf/validator-ajv8': {
    singleton: true,
    eager: true,
  },
  '@simplepoint/components': {
    singleton: true,
    eager: true,
  },
  '@simplepoint/shared': {
    singleton: true,
    eager: true,
  },
  // The feedback bridge owns process-wide UI state, so host and remotes must
  // resolve this exact subpath to one Module Federation instance.
  '@simplepoint/shared/api/feedbackBridge': {
    singleton: true,
    eager: true,
  },
  '@tanstack/react-query': {
    singleton: true,
    eager: true,
  }
}
