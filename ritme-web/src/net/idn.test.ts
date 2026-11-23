import { domainToUnicode as nodeDomainToUnicode } from 'node:url';
import { describe, expect, it } from 'vitest';
import { displayDomain } from './idn';

describe('displayDomain', () => {
  it('leaves ASCII hosts alone', () => {
    expect(displayDomain('cdn.example.com')).toBe('cdn.example.com');
    expect(displayDomain('192.168.0.1')).toBe('192.168.0.1');
    expect(displayDomain('[2001:db8::1]')).toBe('[2001:db8::1]');
  });

  it.each([
    'xn--bcher-kva.example',
    'xn--mnchen-3ya.de',
    'media.xn--fiqs8s',
    'xn--wgv71a119e.jp',
    // Cyrillic and Greek under a TLD of their own script read as intended.
    'xn--e1afmkfd.xn--p1ai',
    'xn--hxajbheg2az3al.xn--jxalpdlp',
  ])(
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

  it.each(['xn--80ak6aa92e.com', 'xn--e1afmkfd.com', 'sub.xn--e1afmkfd.com', 'xn--1-itbiqngd.com', 'xn--hxajbheg2az3al.com', 'xn--80ak6aa92e.com.'])(
    'keeps %s as punycode: a label all in Cyrillic or Greek under an ASCII TLD (whole-script homograph)',
    (host) => {
      expect(displayDomain(host)).toBe(host);
    },
  );

  it('the apple.com look-alike above really is all Cyrillic', () => {
    // "\u0430\u0440\u0440\u04cf\u0435.com" looks like apple.com.
    expect(nodeDomainToUnicode('xn--80ak6aa92e.com')).toBe('\u0430\u0440\u0440\u04cf\u0435.com');
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
