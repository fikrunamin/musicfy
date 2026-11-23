import { describe, expect, it } from 'vitest';
import { canControl, canRemove, isHost, moveDownTarget, moveUpTarget, splitQueue } from './permissions';
import { entry, room } from '../testing/builders';

describe('permissions', () => {
  const locked = room({ settings: { guestsCanControl: false, guestsCanAdd: true } });

  it('host can always control; guests only while guestsCanControl', () => {
    expect(isHost(locked, 'm1')).toBe(true);
    expect(canControl(locked, 'm1')).toBe(true);
    expect(canControl(locked, 'm2')).toBe(false);
    expect(canControl(room(), 'm2')).toBe(true);
    expect(canControl(locked, null)).toBe(false);
  });

  it('anyone can remove their own entry; others need control', () => {
    expect(canRemove(locked, 'm2', entry('e1', { addedBy: 'm2' }))).toBe(true);
    expect(canRemove(locked, 'm2', entry('e1', { addedBy: 'm1' }))).toBe(false);
    expect(canRemove(room(), 'm2', entry('e1', { addedBy: 'm1' }))).toBe(true);
  });
});

describe('queue view and moves', () => {
  const r = room({ queue: ['a', 'b', 'c', 'd', 'e'].map((id) => entry(id)), currentEntryId: 'b' });

  it('splits around the current entry', () => {
    const view = splitQueue(r);
    expect(view.played.map((e) => e.entryId)).toEqual(['a']);
    expect(view.current?.entryId).toBe('b');
    expect(view.upcoming.map((e) => e.entryId)).toEqual(['c', 'd', 'e']);
    expect(splitQueue(room({ queue: [entry('x')] })).upcoming.map((e) => e.entryId)).toEqual(['x']);
  });

  it('moves up: first upcoming cannot, second goes right after current, others after the one two above', () => {
    expect(moveUpTarget(r, 'c')).toBeNull();
    expect(moveUpTarget(r, 'd')).toEqual({ afterEntryId: null });
    expect(moveUpTarget(r, 'e')).toEqual({ afterEntryId: 'c' });
    expect(moveUpTarget(r, 'a')).toBeNull();
  });

  it('moves down: after the next entry; last cannot', () => {
    expect(moveDownTarget(r, 'c')).toEqual({ afterEntryId: 'd' });
    expect(moveDownTarget(r, 'e')).toBeNull();
    expect(moveDownTarget(r, 'b')).toBeNull();
  });

  it('with nothing current, moving the second entry up puts it at the top', () => {
    const idle = room({ queue: ['a', 'b'].map((id) => entry(id)) });
    expect(moveUpTarget(idle, 'a')).toBeNull();
    expect(moveUpTarget(idle, 'b')).toEqual({ afterEntryId: null });
  });
});
