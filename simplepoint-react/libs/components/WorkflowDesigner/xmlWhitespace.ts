/** Protect native moddle output from XML's CR/text and whitespace/attribute normalization.
 * One linear pass only when the native writer emitted CR or tabs; no vendor patch or model mutation.
 */
export function preserveXmlWhitespace(xml: string): string {
  if (!/[\r\t]/.test(xml)) return xml;
  let result = '', inTag = false, quote = '';
  for (let index = 0; index < xml.length; index++) {
    if (!inTag && (xml.startsWith('<!--', index) || xml.startsWith('<?', index))) {
      const ending = xml.startsWith('<!--', index) ? '-->' : '?>';
      const end = xml.indexOf(ending, index + 2);
      if (end < 0) throw Error('BPMN XML 导出格式不正确');
      result += xml.slice(index, end + ending.length); index = end + ending.length - 1; continue;
    }
    if (!inTag && xml.startsWith('<![CDATA[', index)) {
      const end = xml.indexOf(']]>', index + 9);
      if (end < 0) throw Error('BPMN XML 导出格式不正确');
      result += '<![CDATA[' + xml.slice(index + 9, end).replace(/\r/g, ']]>&#13;<![CDATA[') + ']]>';
      index = end + 2; continue;
    }
    const character = xml[index];
    if (inTag && quote) {
      if (character === quote) quote = '';
      result += character === '\r' ? '&#13;' : character === '\t' ? '&#9;' : character === '\n' ? '&#10;' : character;
    } else if (inTag) {
      if (character === '"' || character === "'") quote = character;
      if (character === '>') inTag = false;
      result += character;
    } else {
      if (character === '<') inTag = true;
      result += character === '\r' ? '&#13;' : character;
    }
  }
  return result;
}
