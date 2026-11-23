// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Avatar, Badge, Button, FieldError, IconButton, TextField, initialOf } from './components';
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

describe('IconButton', () => {
  it('takes its accessible name from the label', () => {
    render(<IconButton icon="close" label="Tutup" variant="filled" />);
    expect(screen.getByRole('button', { name: 'Tutup' }).className).toBe('icon-btn icon-btn-filled');
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
