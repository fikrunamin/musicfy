// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it } from 'vitest';
import { NAME_KEY, memoryStore } from '../net/storage';
import { initialSession } from '../session/reducer';
import { HomeScreen } from './home';
import { fakeSession } from '../testing/fake-session';

afterEach(cleanup);

describe('HomeScreen', () => {
  it('creates a room with the trimmed name and remembers it', () => {
    const session = fakeSession();
    const prefs = memoryStore();
    render(<HomeScreen session={session} state={initialSession} routeCode={null} prefs={prefs} />);
    fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: '  Budi ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Mulai Ritme' }));
    expect(session.create).toHaveBeenCalledWith('Budi');
    expect(prefs.get(NAME_KEY)).toBe('Budi');
  });

  it('refuses an empty name and a bad code', () => {
    const session = fakeSession();
    render(<HomeScreen session={session} state={initialSession} routeCode={null} prefs={memoryStore()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Mulai Ritme' }));
    expect(screen.getByRole('alert').textContent).toBe('Nama harus 1–32 karakter.');
    // The message sits under its own field, which is marked invalid and points at it.
    expect(screen.getByLabelText('Nama tampilan').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByLabelText('Nama tampilan').getAttribute('aria-describedby')).toBe(screen.getByRole('alert').id);
    fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: 'Sari' } });
    fireEvent.input(screen.getByLabelText('Kode room'), { target: { value: 'abc' } });
    fireEvent.click(screen.getByRole('button', { name: 'Gabung' }));
    expect(screen.getByRole('alert').textContent).toBe('Kode room terdiri dari 6 huruf/angka.');
    expect(screen.getByLabelText('Nama tampilan').hasAttribute('aria-invalid')).toBe(false);
    expect(screen.getByLabelText('Kode room').getAttribute('aria-invalid')).toBe('true');
    expect(session.join).not.toHaveBeenCalled();
  });

  it('joins the room from the link, offering the app', () => {
    const session = fakeSession();
    const prefs = memoryStore();
    prefs.set(NAME_KEY, 'Sari');
    render(<HomeScreen session={session} state={initialSession} routeCode="K7M2QX" prefs={prefs} />);
    expect(screen.queryByRole('button', { name: 'Mulai Ritme' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Buka di aplikasi Musicfy' }).getAttribute('href')).toBe('musicfy://ritme/K7M2QX');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Kamu diajak dengar bareng');
    expect(screen.getByText('Room K7M2QX')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Gabung' }));
    expect(session.join).toHaveBeenCalledWith('K7M2QX', 'Sari');
  });

  it('explains why the last session ended', () => {
    render(
      <HomeScreen
        session={fakeSession()}
        state={{ ...initialSession, phase: 'ended', ended: { kind: 'rejected', code: 'ROOM_NOT_FOUND' } }}
        routeCode={null}
        prefs={memoryStore()}
      />,
    );
    expect(screen.getByRole('alert').textContent).toBe('Room tidak ditemukan');
  });

  it('accepts a pasted code with spaces', () => {
    const session = fakeSession();
    render(<HomeScreen session={session} state={initialSession} routeCode={null} prefs={memoryStore()} />);
    fireEvent.input(screen.getByLabelText('Nama tampilan'), { target: { value: 'Sari' } });
    const input = screen.getByLabelText('Kode room') as HTMLInputElement;
    fireEvent.input(input, { target: { value: 'k7m 2qx ' } });
    expect(input.value).toBe('K7M2QX');
    fireEvent.click(screen.getByRole('button', { name: 'Gabung' }));
    expect(session.join).toHaveBeenCalledWith('K7M2QX', 'Sari');
  });

  it('keeps the DOM input at 6 characters when a 7th is typed', () => {
    render(<HomeScreen session={fakeSession()} state={initialSession} routeCode={null} prefs={memoryStore()} />);
    const input = screen.getByLabelText('Kode room') as HTMLInputElement;
    fireEvent.input(input, { target: { value: 'K7M2QX' } });
    fireEvent.input(input, { target: { value: 'K7M2QXZ' } });
    expect(input.value).toBe('K7M2QX');
  });
});
