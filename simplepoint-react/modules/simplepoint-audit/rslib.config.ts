import {pluginModuleFederation} from '@module-federation/rsbuild-plugin';
import {pluginReact} from '@rsbuild/plugin-react';
import {defineConfig} from '@rslib/core';

export default defineConfig({
  server: {
    base: '/auditing/mf',
    port: 3002,
  },
  source: {
    tsconfigPath: './tsconfig.json',
  },
  plugins: [pluginReact()],
  lib: [
    {
      format: 'mf',
      output: {
        distPath: {
          root: './dist/mf',
        },
      },
      plugins: [
        pluginModuleFederation({
          name: 'auditing',
          exposes: require('./module.exposes').default,
          shared: require('@simplepoint/shared/types/module.shared').default,
        }),
      ],
    },
    {
      format: 'esm',
      dts: true,
      output: {
        distPath: {
          root: './dist/esm',
        },
      },
    },
    {
      format: 'cjs',
      dts: true,
      output: {
        distPath: {
          root: './dist/cjs',
        },
      },
    },
  ]
});
