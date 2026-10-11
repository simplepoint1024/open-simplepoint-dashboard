import assert from 'node:assert/strict';
import test from 'node:test';
import {preserveXmlWhitespace} from './xmlWhitespace.ts';

test('native formatted output without problematic characters is an unchanged fast path', () => {
  const xml = '<?xml version="1.0"?>\n<root value="line&#10;next">line\nnext &amp; data</root>';
  assert.equal(preserveXmlWhitespace(xml), xml);
});
test('CR in text and raw XML attribute whitespace are encoded without rewriting other values', () => {
  assert.equal(preserveXmlWhitespace('<root value="a\tb\rc\nd">a\rb\tc\nd</root>'),
    '<root value="a&#9;b&#13;c&#10;d">a&#13;b\tc\nd</root>');
  assert.equal(preserveXmlWhitespace("<root value='a\rb' other='x>y'/>"), "<root value='a&#13;b' other='x>y'/>");
});
test('CDATA CR is represented outside the literal section, never as literal entity text', () => {
  assert.equal(preserveXmlWhitespace('<root><![CDATA[a\rb<>&]]></root>'),
    '<root><![CDATA[a]]>&#13;<![CDATA[b<>&]]></root>');
});
test('comments and processing instructions are not mistaken for model attributes or text', () => {
  const xml = '<?xml version="1.0"?>\n<!-- quoted "a\rb" > -->\n<root>a\rb</root>';
  assert.equal(preserveXmlWhitespace(xml), '<?xml version="1.0"?>\n<!-- quoted "a\rb" > -->\n<root>a&#13;b</root>');
});
