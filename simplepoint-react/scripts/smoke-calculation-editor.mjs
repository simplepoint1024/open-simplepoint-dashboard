import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {readFile, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';

const dist = fileURLToPath(new URL('../libs/calculation-editor/demo/dist/', import.meta.url));
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const file = path.resolve(dist, '.' + (pathname === '/' ? '/index.html' : pathname));
    if (!file.startsWith(dist)) throw Error('Invalid path');
    const data = await readFile(file);
    res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
    res.end(data);
  } catch {res.statusCode = 404; res.end();}
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const profile = await mkdtemp(path.join(tmpdir(), 'sp-calculator-'));
const chrome = spawn(process.env.CHROME_BIN ?? '/usr/bin/google-chrome-stable', [
  '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-background-networking',
  '--user-data-dir=' + profile, '--remote-debugging-port=0', 'about:blank',
], {stdio: 'ignore'});
let socket;
const poll = async (check, label) => {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {const result = await check(); if (result) return result; await delay(75);}
  throw Error('Timed out: ' + label);
};
try {
  const port = await poll(async () => {try {return (await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0];} catch {return undefined;}}, 'browser startup');
  const target = (await (await fetch('http://127.0.0.1:' + port + '/json/list')).json()).find(item => item.type === 'page');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
  const pending = new Map(), errors = [], requests = []; let sequence = 0;
  socket.addEventListener('message', ({data}) => {
    const event = JSON.parse(data);
    if (event.id) {const call = pending.get(event.id); pending.delete(event.id); event.error ? call.reject(Error(JSON.stringify(event.error))) : call.resolve(event.result);}
    if (event.method === 'Runtime.exceptionThrown') errors.push(event.params.exceptionDetails);
    if (event.method === 'Network.requestWillBeSent') requests.push(event.params.request.url);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {const id = ++sequence; pending.set(id, {resolve, reject}); socket.send(JSON.stringify({id, method, params}));});
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const fill = (selector, value) => evaluate(`(() => {const input=document.querySelector(${JSON.stringify(selector)});Object.getOwnPropertyDescriptor(input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(input,${JSON.stringify(value)});input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  const clickText = (selector, text) => evaluate(`[...document.querySelectorAll(${JSON.stringify(selector)})].find(e=>e.textContent.trim()===${JSON.stringify(text)}).click()`);
  await send('Runtime.enable'); await send('Network.enable'); await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 800, deviceScaleFactor: 1, mobile: false});
  await send('Page.navigate', {url: 'http://127.0.0.1:' + server.address().port + '/'});
  await poll(() => evaluate("!!document.querySelector('.sp-calculator-block')"), 'standalone editor');
  assert.equal(await evaluate("document.querySelectorAll('.sp-calculator-block[data-kind=FIELD]').length"), 2);
  // Select a concrete literal slot, then drag a field from the palette through browser events.
  await clickText('.sp-calculator-block-label', '2');
  await poll(() => evaluate("document.querySelector('.sp-calculator-block.is-selected > button')?.textContent==='2'"), 'literal selection');
  const positions = await evaluate(`(() => {const source=[...document.querySelectorAll('.sp-calculator-palette-items button')].find(e=>e.textContent==='数量').getBoundingClientRect(), target=document.querySelector('.sp-calculator-block.is-selected').getBoundingClientRect();return {source:{x:source.x+source.width/2,y:source.y+source.height/2},target:{x:target.x+target.width/2,y:target.y+target.height/2}};})()`);
  await send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...positions.source});
  await send('Input.dispatchMouseEvent', {type: 'mousePressed', button: 'left', clickCount: 1, ...positions.source});
  for (let step = 1; step <= 12; step++) {
    await send('Input.dispatchMouseEvent', {type: 'mouseMoved', button: 'left', buttons: 1,
      x: positions.source.x + (positions.target.x - positions.source.x) * step / 12,
      y: positions.source.y + (positions.target.y - positions.source.y) * step / 12});
    await delay(25);
  }
  await delay(200);
  await send('Input.dispatchMouseEvent', {type: 'mouseMoved', button: 'left', buttons: 1, ...positions.target});
  await delay(200);
  await send('Input.dispatchMouseEvent', {type: 'mouseReleased', button: 'left', clickCount: 1, ...positions.target});
  await poll(() => evaluate("document.querySelector('output').textContent==='=ROUND(([单价]*[数量]),[数量])'"), 'pointer drag changes formula');
  // Native keyboard activation uses the same insertion contract as a drop.
  await evaluate("[...document.querySelectorAll('.sp-calculator-palette-items button')].find(e=>e.textContent==='Text').focus()");
  await poll(() => evaluate("document.activeElement?.textContent==='Text'"), 'palette focus');
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r'});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  await poll(() => evaluate("document.querySelector('.sp-calculator-properties input')?.value===''"), 'keyboard insertion');
  await fill('.sp-calculator-properties input', '汉😀');
  await poll(() => evaluate("document.querySelector('output').textContent.includes('汉😀')"), 'Unicode literal');
  await clickText('[role=tab]', 'ƒ Expression');
  await fill('textarea', '=SUM([单价],0.123456789012345678901234567890)');
  await poll(() => evaluate("!document.querySelector('[role=alert]')"), 'valid expression');
  await clickText('[role=tab]', '▦ Visual');
  await poll(() => evaluate("document.querySelector('.sp-calculator-block-label')?.textContent==='SUM'"), 'text parsed back into visual blocks');
  await clickText('[role=tab]', 'ƒ Expression');
  await fill('textarea', '=SUM(');
  await poll(() => evaluate("document.querySelector('[role=alert]')?.textContent.includes('SYNTAX')"), 'precise syntax feedback');
  await clickText('[role=tab]', '▦ Visual');
  assert.equal(await evaluate("document.querySelector('output').textContent"), '=SUM(');
  assert.equal(await evaluate("[...document.querySelectorAll('.sp-calculator-palette-items button')].every(e=>e.disabled)"), true);
  await clickText('[role=tab]', 'ƒ Expression'); await fill('textarea', '=SUM([单价],[数量])');
  await clickText('[role=tab]', '▦ Visual');
  for (const width of [1280, 768, 390]) {
    await send('Emulation.setDeviceMetricsOverride', {width, height: 800, deviceScaleFactor: 1, mobile: false});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    assert.ok(await evaluate("document.documentElement.scrollWidth<=innerWidth"), 'bounded editor at width ' + width);
  }
  const screenshot = process.env.CALCULATION_SCREENSHOT;
  if (screenshot) await writeFile(screenshot, Buffer.from((await send('Page.captureScreenshot', {format: 'png'})).data, 'base64'));
  assert.deepEqual(errors, []);
  assert.ok(requests.every(url => url.startsWith('http://127.0.0.1:') || url.startsWith('data:')), 'no platform or external service dependency');
  console.log('PASS: standalone editor pointer drag, keyboard insertion, Unicode editing, expression round-trip, invalid draft preservation and bounded PC/tablet/phone layout');
} finally {
  socket?.close(); chrome.kill('SIGTERM'); server.close(); await delay(300); await rm(profile, {recursive: true, force: true});
}
