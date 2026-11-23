// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeSession } from '../testing/fake-session';
import { copyLink, roomLink, roomLinkLabel, share } from './share';

/** jsdom has neither navigator.share nor navigator.clipboard; tests add them per case. */
function stubNavigator(props: { share?: unknown; clipboard?: unknown }) {
  for (const [key, value] of Object.entries(props)) {
    Object.defineProperty(navigator, key, { configurable: true, value });
  }
}

afterEach(() => {
  stubNavigator({ share: undefined, clipboard: undefined });
  vi.unstubAllGlobals();
});

describe('roomLink', () => {
  it('is the /r/{code} link on this origin, and its label drops the scheme', () => {
    expect(roomLink('K7M2QX')).toBe(`${location.origin}/r/K7M2QX`);
    expect(roomLinkLabel('K7M2QX')).toBe(`${location.host}/r/K7M2QX`);
    expect(roomLinkLabel('K7M2QX')).not.toMatch(/^https?:/);
  });
});

describe('copyLink', () => {
  it('copies the room link and shows "Link disalin"', async () => {
    const writeText = vi.fn(async () => {});
    stubNavigator({ clipboard: { writeText } });
    const session = fakeSession();
    await copyLink('K7M2QX', session);
    expect(writeText).toHaveBeenCalledWith(`${location.origin}/r/K7M2QX`);
    expect(session.notify).toHaveBeenCalledWith('linkCopied');
  });

  it('falls back to a prompt with the link when the clipboard refuses or is missing', async () => {
    const prompt = vi.fn(() => null);
    vi.stubGlobal('prompt', prompt);
    stubNavigator({ clipboard: { writeText: vi.fn(async () => Promise.reject(new Error('denied'))) } });
    const session = fakeSession();
    await copyLink('K7M2QX', session);
    expect(prompt).toHaveBeenCalledWith('Bagikan', `${location.origin}/r/K7M2QX`);
    expect(session.notify).not.toHaveBeenCalled();
    stubNavigator({ clipboard: undefined }); // http pages have no clipboard API at all
    await copyLink('K7M2QX', session);
    expect(prompt).toHaveBeenCalledTimes(2);
  });
});

describe('share', () => {
  it('uses the share sheet when there is one', async () => {
    const native = vi.fn(async () => {});
    const writeText = vi.fn(async () => {});
    stubNavigator({ share: native, clipboard: { writeText } });
    const session = fakeSession();
    await share('K7M2QX', session);
    expect(native).toHaveBeenCalledWith({ title: 'Ritme', text: 'Yuk dengar bareng di Ritme', url: `${location.origin}/r/K7M2QX` });
    expect(writeText).not.toHaveBeenCalled();
  });

  it('does nothing more when the user dismisses the share sheet', async () => {
    const writeText = vi.fn(async () => {});
    stubNavigator({ share: vi.fn(async () => Promise.reject(new DOMException('cancel', 'AbortError'))), clipboard: { writeText } });
    await share('K7M2QX', fakeSession());
    expect(writeText).not.toHaveBeenCalled();
  });

  it('copies the link instead when there is no share sheet or it fails', async () => {
    const writeText = vi.fn(async () => {});
    stubNavigator({ clipboard: { writeText } });
    const session = fakeSession();
    await share('K7M2QX', session);
    expect(writeText).toHaveBeenCalledTimes(1);
    stubNavigator({ share: vi.fn(async () => Promise.reject(new DOMException('no', 'NotAllowedError'))) });
    await share('K7M2QX', session);
    expect(writeText).toHaveBeenCalledTimes(2);
    expect(session.notify).toHaveBeenCalledWith('linkCopied');
  });
});
