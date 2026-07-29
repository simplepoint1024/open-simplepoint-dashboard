import {pluginModuleFederation} from '@module-federation/rsbuild-plugin';
import {pluginReact} from '@rsbuild/plugin-react';
import {defineConfig} from '@rslib/core';

export default defineConfig({
  server: {
    base: '/ai/mf',
    port: 3004,
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
          name: 'ai',
          manifest: {
            // Rspack 2.1.x can fail while Module Federation recursively converts
            // compilation stats. The runtime only needs the remote entry and
            // expose map, both of which are emitted without the costly analysis.
            disableAssetsAnalyze: true,
          },
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
  ],
});
