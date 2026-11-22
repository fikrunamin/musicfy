import { render } from 'preact';
import { App } from './app';
import { RitmeConnection, type SocketLike, ritmeSocketUrl } from './net/connection';
import { safeStore } from './net/storage';
import { Session } from './session/session';
import './styles.css';

const prefs = safeStore(() => localStorage);
const tabStore = safeStore(() => sessionStorage);
const now = () => performance.now();

const session = new Session({
  now,
  connect: (onEvent) =>
    new RitmeConnection({
      url: ritmeSocketUrl(location),
      socketFactory: (url) => new WebSocket(url) as unknown as SocketLike,
      storage: tabStore,
      now,
      onEvent,
    }),
});

// The session notices device sleep on wake (stale clock) and asks the player to re-sync.
document.addEventListener('visibilitychange', () => session.visibilityChanged(document.visibilityState === 'visible'));

render(<App session={session} prefs={prefs} tabStore={tabStore} />, document.getElementById('app')!);
