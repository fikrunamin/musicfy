import { domainToUnicode as nodeDomainToUnicode } from 'node:url';
import { describe, expect, it } from 'vitest';
import { displayDomain } from './idn';

describe('displayDomain', () => {
  it('leaves ASCII hosts alone', () => {
    expect(displayDomain('cdn.example.com')).toBe('cdn.example.com');
    expect(displayDomain('192.168.0.1')).toBe('192.168.0.1');
    expect(displayDomain('[2001:db8::1]')).toBe('[2001:db8::1]');
  });

  it.each(['xn--bcher-kva.example', 'xn--mnchen-3ya.de', 'media.xn--fiqs8s', 'xn--80ak6aa92e.com', 'xn--wgv71a119e.jp'])(
    'decodes %s like node:url domainToUnicode',
    (host) => {
      expect(displayDomain(host)).toBe(nodeDomainToUnicode(host));
    },
  );

  it('keeps a label that mixes Latin with Cyrillic or Greek as punycode (homograph)', () => {
    // "p\u0430ypal" with a Cyrillic \u0430.
    const mixed = 'xn--pypal-4ve.com';
    expect(nodeDomainToUnicode(mixed)).toBe('p\u0430ypal.com');
    expect(displayDomain(mixed)).toBe(mixed);
  });

  it('keeps a label that decodes to symbols (emoji) as punycode, like browsers do', () => {
    expect(displayDomain('xn--ls8h.la')).toBe('xn--ls8h.la');
  });

  it('keeps malformed punycode and labels that decode to invisible or odd characters as they are', () => {
    expect(displayDomain('xn--.example')).toBe('xn--.example');
    expect(displayDomain('xn--zz-!!.example')).toBe('xn--zz-!!.example');
    expect(displayDomain('xn--a.example')).toBe('xn--a.example');
  });
});
