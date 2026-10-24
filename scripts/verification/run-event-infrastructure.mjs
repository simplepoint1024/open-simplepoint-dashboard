#!/usr/bin/env node
import assert from 'node:assert/strict';
import {randomBytes, randomUUID} from 'node:crypto';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {setTimeout as delay} from 'node:timers/promises';
import {readFile, readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

// These ports/databases are the isolated contracts of the existing JDBC/Rabbit integration tests.
const root = fileURLToPath(new URL('../../', import.meta.url));
const run = promisify(execFile);
const suffix = randomUUID().slice(0, 12);
const postgres = 'sp-event-verify-pg-' + suffix, rabbit = 'sp-event-verify-rabbit-' + suffix;
const password = randomBytes(32).toString('base64url');
const environment = {...process.env, POSTGRES_PASSWORD: password, RABBITMQ_DEFAULT_PASS: password,
  SP_EVENT_TEST_POSTGRES_PASSWORD: password, SP_EVENT_TEST_RABBIT_PASSWORD: password};
const docker = args => run('docker', args, {cwd: root, env: environment, timeout: 20000, maxBuffer: 1024 * 1024});
const ready = async (name, command) => {
  for (let attempt = 0; attempt < 90; attempt++) {
    try {await docker(['exec', name, ...command]); return;} catch {await delay(500);}
  }
  throw new Error('Isolated event test dependency did not become ready: ' + name);
};

try {
  await docker(['run', '--detach', '--rm', '--pull=never', '--name', postgres,
    '--publish', '127.0.0.1:35432:5432', '--memory', '512m',
    '--env', 'POSTGRES_PASSWORD', '--env', 'POSTGRES_DB=event_verification',
    process.env.SP_EVENT_TEST_POSTGRES_IMAGE || 'postgres:17']);
  await docker(['run', '--detach', '--rm', '--pull=never', '--name', rabbit,
    '--publish', '127.0.0.1:35672:5672', '--memory', '512m',
    '--env', 'RABBITMQ_DEFAULT_USER=app', '--env', 'RABBITMQ_DEFAULT_PASS',
    '--env', 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2',
    process.env.SP_EVENT_TEST_RABBIT_IMAGE || 'rabbitmq:4.2-management']);
  await Promise.all([
    ready(postgres, ['pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'event_verification']),
    ready(rabbit, ['rabbitmq-diagnostics', '-q', 'check_running']),
  ]);
  console.log('Isolated PostgreSQL and RabbitMQ ready; credentials remain in process environment.');
  const modules = ['simplepoint-events-jdbc', 'simplepoint-events-rabbit'];
  const child = spawn('./gradlew', ['--max-workers=2', '--no-parallel', '--rerun-tasks',
    '-Psimplepoint.frontend.skip=true', ...modules.map(module => `:simplepoint-events:${module}:test`)],
  {cwd: root, env: environment, stdio: 'inherit'});
  const code = await new Promise((resolve, reject) => {child.once('error', reject); child.once('exit', resolve);});
  assert.equal(code, 0, 'Event integration tests must pass');
  const suites = [];
  for (const module of modules) {
    const folder = root + `simplepoint-events/${module}/build/test-results/test/`;
    for (const file of (await readdir(folder)).filter(file => /^TEST-.*\.xml$/.test(file))) {
      const content = await readFile(folder + file, 'utf8');
      const header = content.match(/<testsuite\b[^>]*>/)?.[0]; assert(header);
      const number = key => Number(header.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1] ?? NaN);
      const counts = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(key => [key, number(key)]));
      assert(counts.tests > 0 && counts.failures === 0 && counts.errors === 0 && counts.skipped === 0,
        'No failed or skipped integration suites: ' + file);
      suites.push({file, ...counts});
    }
  }
  assert(suites.some(suite => suite.file.endsWith('.RabbitEventIntegrationTest.xml') && suite.tests >= 4));
  console.log(JSON.stringify({passed: true, isolatedDependencies: true, suites}, null, 2));
} finally {
  // Only these uniquely named test containers belong to this run; business infrastructure is untouched.
  await Promise.allSettled([docker(['rm', '--force', '--volumes', postgres]), docker(['rm', '--force', '--volumes', rabbit])]);
  for (const name of [postgres, rabbit]) {
    try {await docker(['inspect', name]); throw new Error('Test container was not removed: ' + name);}
    catch (error) {if (error.code !== 1) throw error;}
  }
}
