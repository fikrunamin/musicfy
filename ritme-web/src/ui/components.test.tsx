// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Avatar, AvatarStack, Badge, Button, DurationChip, EqBars, FieldError, IconButton, LinkButton, SyncDot, TextField, initialOf } from './components';
import { ICON_PATHS } from './icon-paths';
import { MediaRow, Thumb, VideoPlaceholder } from './media';

afterEach(cleanup);

describe('Button', () => {
  it('is a type=button pill of the given variant with a decorative icon', () => {
    const onClick = vi.fn();
    render(
      <Button variant="primary" icon="add" onClick={onClick}>
        Tambah lagu
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Tambah lagu' });
    expect(button.getAttribute('type')).toBe('button');
    expect(button.className).toBe('btn btn-primary');
    expect(button.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalled();
  });
});

describe('Button variants', () => {
  it('can be a full-width submit button with extra classes, and stays a button when disabled', () => {
    render(
      <Button type="submit" variant="danger" block class="extra" disabled>
        Akhiri
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Akhiri' }) as HTMLButtonElement;
    expect(button.getAttribute('type')).toBe('submit');
    expect(button.className).toBe('btn btn-danger btn-block extra');
    expect(button.disabled).toBe(true);
    expect(button.querySelector('svg')).toBeNull();
  });
});

describe('LinkButton', () => {
  it('is a link styled as a pill, with its href and a decorative icon', () => {
    render(
      <LinkButton href="musicfy://ritme/K7M2QX" icon="open_in_new" block>
        Buka di aplikasi Musicfy
      </LinkButton>,
    );
    const link = screen.getByRole('link', { name: 'Buka di aplikasi Musicfy' });
    expect(link.getAttribute('href')).toBe('musicfy://ritme/K7M2QX');
    expect(link.className).toBe('btn btn-secondary btn-block');
    expect(link.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });
});

describe('IconButton', () => {
  it('takes its accessible name from the label', () => {
    render(<IconButton icon="close" label="Tutup" variant="filled" />);
    expect(screen.getByRole('button', { name: 'Tutup' }).className).toBe('icon-btn icon-btn-filled');
  });

  it('keeps the label as its name even when an aria-label is passed along', () => {
    render(<IconButton icon="close" label="Tutup" aria-label="Lain" />);
    expect(screen.getByRole('button').getAttribute('aria-label')).toBe('Tutup');
  });

  it('is type=button by default, submit when asked, and the 64 px play button at lg', () => {
    render(
      <>
        <IconButton icon="play_arrow" label="Putar" size="lg" variant="accent" />
        <IconButton icon="search" label="Pratinjau" type="submit" />
      </>,
    );
    const play = screen.getByRole('button', { name: 'Putar' });
    expect(play.getAttribute('type')).toBe('button');
    expect(play.className).toBe('icon-btn icon-btn-accent icon-btn-lg');
    expect(play.querySelector('svg')?.getAttribute('width')).toBe('32');
    expect(screen.getByRole('button', { name: 'Pratinjau' }).getAttribute('type')).toBe('submit');
  });
});

describe('TextField and FieldError', () => {
  it('labels the input and points it at its error', () => {
    render(
      <>
        <TextField id="name" label="Nama tampilan" placeholder="Misalnya Budi" errorId="name-error" />
        <FieldError id="name-error">Nama harus 1–32 karakter.</FieldError>
      </>,
    );
    const input = screen.getByLabelText('Nama tampilan');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('name-error');
    expect(screen.getByRole('alert').textContent).toBe('Nama harus 1–32 karakter.');
  });

  it('is valid without an error', () => {
    render(<TextField id="code" label="Kode room" />);
    expect(screen.getByLabelText('Kode room').hasAttribute('aria-invalid')).toBe(false);
  });
});

describe('Badge and Avatar', () => {
  it('says Video, LIVE (with a dot) and Host', () => {
    render(
      <>
        <Badge kind="video" />
        <Badge kind="live" />
        <Badge kind="host" />
      </>,
    );
    expect(screen.getByText('Video').className).toBe('badge badge-video');
    expect(screen.getByText('LIVE').querySelector('.badge-dot')).toBeTruthy();
    expect(screen.getByText('Host')).toBeTruthy();
  });

  it('shows an emoji-safe uppercase initial', () => {
    expect(initialOf('  budi')).toBe('B');
    expect(initialOf('😀 Sari')).toBe('😀');
    expect(initialOf('')).toBe('?');
    render(<Avatar name="sari" />);
    expect(screen.getByText('S').getAttribute('aria-hidden')).toBe('true');
  });

  it('names its sizes from small to large: xs 22, sm 32 (default), md 36, lg 44', () => {
    const { container } = render(
      <>
        <Avatar name="a" size="xs" />
        <Avatar name="b" />
        <Avatar name="c" size="md" />
        <Avatar name="d" size="lg" />
      </>,
    );
    expect([...container.querySelectorAll('.avatar')].map((a) => a.className)).toEqual([
      'avatar avatar-xs',
      'avatar avatar-sm',
      'avatar avatar-md',
      'avatar avatar-lg',
    ]);
  });
});

describe('AvatarStack, DurationChip, SyncDot, EqBars', () => {
  it('stacks the first three members at the 36 px size, hidden from assistive tech', () => {
    const { container } = render(<AvatarStack names={['Budi', 'Sari', 'Rani', 'Tono']} />);
    const stack = container.querySelector('.avatar-stack')!;
    expect(stack.getAttribute('aria-hidden')).toBe('true');
    expect([...stack.querySelectorAll('.avatar.avatar-md')].map((a) => a.textContent)).toEqual(['B', 'S', 'R']);
  });

  it('puts the duration in its chip', () => {
    render(<DurationChip>24:18</DurationChip>);
    expect(screen.getByText('24:18').className).toBe('chip-duration');
  });

  it('marks the sync state on a decorative dot', () => {
    const { container } = render(
      <>
        <SyncDot state="online" />
        <SyncDot state="reconnecting" />
      </>,
    );
    const dots = [...container.querySelectorAll('.sync-dot')];
    expect(dots.map((d) => d.getAttribute('data-state'))).toEqual(['online', 'reconnecting']);
    expect(dots.every((d) => d.getAttribute('aria-hidden') === 'true')).toBe(true);
  });

  it('draws four decorative equalizer bars that move only while playing', () => {
    const { container } = render(
      <>
        <EqBars playing />
        <EqBars playing={false} />
      </>,
    );
    const [on, off] = [...container.querySelectorAll('.eq')];
    expect(on.getAttribute('data-playing')).toBe('true');
    expect(off.getAttribute('data-playing')).toBe('false');
    expect(on.querySelectorAll('i').length).toBe(4);
    expect(on.getAttribute('aria-hidden')).toBe('true');
  });
});

describe('Thumb', () => {
  it('shows an https image, 44 × 44 by default and 78 × 44 when wide', () => {
    const square = render(<Thumb url="https://i.ytimg.com/a.jpg" />).container.querySelector('img.thumb')!;
    expect(square.getAttribute('width')).toBe('44');
    expect(square.getAttribute('height')).toBe('44');
    expect(square.classList.contains('thumb-wide')).toBe(false);
    const { container } = render(<Thumb url="https://i.ytimg.com/a.jpg" wide duration="24:18" />);
    const img = container.querySelector('img.thumb.thumb-wide')!;
    expect(img.getAttribute('width')).toBe('78');
    expect(img.getAttribute('height')).toBe('44');
    expect(img.getAttribute('referrerpolicy')).toBe('no-referrer');
    expect(container.querySelector('.thumb-frame .chip-duration')?.textContent).toBe('24:18');
  });

  it('without an image: the play mark when wide, the note when square', () => {
    const wide = render(<Thumb url={null} wide />).container.querySelector('.thumb-empty path')!;
    expect(wide.getAttribute('d')).toBe(ICON_PATHS.play_arrow);
    const square = render(<Thumb url={null} />).container.querySelector('.thumb-empty path')!;
    expect(square.getAttribute('d')).toBe(ICON_PATHS.music_note);
  });

  it('shows the film placeholder for a direct link without an image, and never a non-https URL', () => {
    const { container } = render(<Thumb url="http://x.example/a.jpg" wide kind="direct" />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('.thumb-empty.thumb-direct svg')).toBeTruthy();
  });
});

describe('VideoPlaceholder', () => {
  it('shows the domain, badge and duration', () => {
    render(<VideoPlaceholder domain="media.example.com" badge={<Badge kind="video" />} duration="12:04" />);
    expect(screen.getByText('media.example.com')).toBeTruthy();
    expect(screen.getByText('Video')).toBeTruthy();
    expect(screen.getByText('12:04')).toBeTruthy();
  });
});

describe('MediaRow', () => {
  it('replaces the meta with the warning when the entry cannot play here', () => {
    render(
      <ul>
        <MediaRow thumb={null} title="Lagu Rahasia" meta="Artis · oleh Budi" warning="Tidak bisa diputar di web" />
      </ul>,
    );
    expect(screen.getByText('Tidak bisa diputar di web').className).toBe('row-warning');
    expect(screen.queryByText('Artis · oleh Budi')).toBeNull();
  });

  it('wraps a plain-text meta in a span so it can ellipsize', () => {
    const { container } = render(
      <ul>
        <MediaRow thumb={null} title="Lagu" meta="Artis · oleh Budi" />
      </ul>,
    );
    const span = container.querySelector('.meta-track > span');
    expect(span?.textContent).toBe('Artis · oleh Budi');
  });
});
