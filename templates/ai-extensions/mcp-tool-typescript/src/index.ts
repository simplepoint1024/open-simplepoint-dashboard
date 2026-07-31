import { pathToFileURL } from 'node:url';

import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';

export function createServer(): McpServer {
  const server = new McpServer(
    {
      name: '__EXTENSION_NAME__',
      version: '1.0.0',
    },
    {
      instructions:
        'Use echo when the caller needs the supplied message returned unchanged.',
    },
  );

  server.registerTool(
    'echo',
    {
      title: 'Echo',
      description: 'Returns the supplied message unchanged.',
      inputSchema: z.object({
        message: z.string().min(1).max(4096),
      }),
      outputSchema: z.object({
        message: z.string(),
      }),
    },
    async ({ message }) => ({
      content: [
        {
          type: 'text',
          text: message,
        },
      ],
      structuredContent: {
        message,
      },
    }),
  );

  return server;
}

const entrypoint = process.argv[1];
if (entrypoint && import.meta.url === pathToFileURL(entrypoint).href) {
  const handle = serveStdio(createServer, {
    legacy: 'serve',
    onerror: (error: Error) => {
      console.error('MCP server failed', error);
      process.exitCode = 1;
    },
  });
  process.once('SIGINT', () => {
    void handle.close().finally(() => {
      process.exit(0);
    });
  });
}
