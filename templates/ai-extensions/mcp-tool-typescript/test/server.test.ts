import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

test('discovers and calls the echo Tool over MCP stdio', async () => {
  const serverPath = fileURLToPath(
    new URL('../src/index.js', import.meta.url),
  );
  const client = new Client({
    name: '__EXTENSION_NAME__-test',
    version: '1.0.0',
  });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [serverPath],
    stderr: 'pipe',
  });

  try {
    await client.connect(transport);
    const discovered = await client.listTools();
    assert.deepEqual(
      discovered.tools.map((tool) => tool.name),
      ['echo'],
    );

    const result = await client.callTool({
      name: 'echo',
      arguments: {
        message: 'open-simplepoint-mcp-tool-ok',
      },
    });
    assert.equal(result.isError ?? false, false);
    assert.deepEqual(result.structuredContent, {
      message: 'open-simplepoint-mcp-tool-ok',
    });
  } finally {
    await client.close();
  }
});
