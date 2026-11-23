import { readFileSync, readdirSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (name: string) => readFileSync(new URL(`./${name}`, import.meta.url), 'utf8');
const css = read('motion.css');
const base = read('base.css');
const imports = readFileSync(new URL('../styles.css', import.meta.url), 'utf8');
const sheets = readdirSync(new URL('.', import.meta.url)).filter((f) => f.endsWith('.css'));

const ALLOWED = '@media (prefers-reduced-motion: no-preference)';

/** The body of the block that starts at `start` (balanced braces). */
function blockAt(source: string, start: number): string {
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(open + 1, i);
  }
  throw new Error('unbalanced braces');
}

/** Every `@media (prefers-reduced-motion: no-preference) { … }` block, joined. */
const allowed = (() => {
  const parts: string[] = [];
  for (let at = css.indexOf(ALLOWED); at >= 0; at = css.indexOf(ALLOWED, at + 1)) parts.push(blockAt(css, at));
  return parts.join('\n');
})();

/** motion.css without those blocks and without its @keyframes. */
const outside = (() => {
  let rest = css;
  for (const marker of [ALLOWED, '@keyframes']) {
    for (let at = rest.indexOf(marker); at >= 0; at = rest.indexOf(marker)) {
      const body = blockAt(rest, at);
      const end = rest.indexOf(body, at) + body.length + 1;
      rest = rest.slice(0, at) + rest.slice(end);
    }
  }
  return rest;
})();

const keyframes = (name: string) => {
  const at = css.indexOf(`@keyframes ${name} `);
  return at < 0 ? null : blockAt(css, at);
};

/** The declarations of the rule whose selector list is exactly `selector`, inside the allowed blocks. */
function rule(selector: string): string | null {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+');
  const match = new RegExp(`(?:^|[}\\n])\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(allowed);
  return match ? match[1] : null;
}

/** [selector list, declarations] of every plain rule in a stylesheet (media blocks flattened). */
function rules(source: string): [string, string][] {
  const out: [string, string][] = [];
  const re = /([^{}@;]+)\{([^{}]*)\}/g;
  const flat = source.replace(/\/\*[\s\S]*?\*\//g, '');
  for (let m = re.exec(flat); m; m = re.exec(flat)) out.push([m[1].trim(), m[2]]);
  return out;
}

describe('motion.css', () => {
  it('is imported after the screens it animates', () => {
    expect(imports).toContain("@import './styles/motion.css';");
    expect(imports.indexOf('motion.css')).toBeGreaterThan(imports.indexOf('desktop.css'));
  });

  it('animates nothing unless the user allows motion; reduced motion stays the global kill switch', () => {
    expect(allowed).not.toBe('');
    expect(outside).not.toMatch(/animation|transition/);
    const reduce = blockAt(base, base.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduce).toMatch(/animation:\s*none\s*!important/);
    expect(reduce).toMatch(/transition:\s*none\s*!important/);
  });

  it('keeps every other stylesheet still: their motion sits behind the same media query', () => {
    for (const name of sheets.filter((f) => f !== 'motion.css' && f !== 'base.css')) {
      const source = read(name);
      let rest = source;
      for (let at = rest.indexOf(ALLOWED); at >= 0; at = rest.indexOf(ALLOWED)) {
        const body = blockAt(rest, at);
        rest = rest.slice(0, at) + rest.slice(rest.indexOf(body, at) + body.length + 1);
      }
      expect(rest, name).not.toMatch(/animation|transition|@keyframes/);
    }
  });

  it('raises sheets in 280 ms ease-out', () => {
    expect(rule('.sheet')).toMatch(/animation:\s*sheet-in\s+280ms\s+ease-out\s*;/);
    expect(keyframes('sheet-in')).toMatch(/top:\s*100%/);
    expect(rule('.sheet-scrim')).toMatch(/animation:\s*fade-in\s+280ms\s+ease-out\s*;/);
  });

  it('gives the sheet rise an end it can interpolate to (top: 100% -> auto is discrete and snaps)', () => {
    const zero = /(^|[\s;{])top:\s*0(px)?\s*(;|$)/;
    const inFrames = /(^|[\s}])(to|100%)\s*\{([^}]*)\}/.exec(keyframes('sheet-in')!);
    const onSheet = sheets.some((name) =>
      rules(read(name)).some(([selectors, decls]) => selectors.split(',').some((s) => s.trim() === '.sheet') && zero.test(decls)),
    );
    expect(Boolean(inFrames && zero.test(inFrames[3])) || onSheet).toBe(true);
  });

  it('never gives a sheet or its layer a containing block: the kick/end dialog inside stays fixed to the viewport', () => {
    // transform, translate, scale, rotate, perspective, filter, backdrop-filter, contain and
    // will-change on an ancestor would pin the position: fixed ConfirmDialog to the sheet instead.
    const trap = /(^|[\s;{])(transform|translate|scale|rotate|perspective|filter|backdrop-filter|contain|container-type|will-change)\s*:/;
    expect(keyframes('sheet-in')).not.toMatch(trap);
    expect(keyframes('fade-in')).not.toMatch(trap);
    // The animation leaves nothing behind once it ends (no fill mode that holds the first frame).
    expect(rule('.sheet')).not.toMatch(/forwards|both|backwards/);
    const ancestor = /(^|[\s>+~,])\.(sheet|sheet-layer|add-sheet|members-sheet|members-panel)(?![\w-])[^\s,]*\s*$/;
    for (const name of sheets) {
      for (const [selectors, decls] of rules(read(name))) {
        for (const selector of selectors.split(',')) {
          if (ancestor.test(selector.trim())) expect(decls, `${name}: ${selector.trim()}`).not.toMatch(trap);
        }
      }
    }
  });

  it('fades the player in over 400 ms by its own opacity, with nothing laid over it', () => {
    expect(rule('.player')).toMatch(/animation:\s*player-in\s+400ms\s+ease-out\s*;/);
    const frames = keyframes('player-in')!;
    expect(frames).toMatch(/opacity:\s*0/);
    expect(frames.replace(/opacity:\s*[\d.]+;?/g, '').replace(/from|to|[{}\s]/g, '')).toBe('');
    // The box under the iframe is clear, so the art shows through until the video paints (not black).
    const player = rules(read('room.css')).find(([sel]) => sel === '.player')![1];
    expect(player).toMatch(/background:\s*transparent/);
  });

  it('keeps the stage art under the player: nothing lifts it, nothing on it takes a tap', () => {
    let seen = false;
    for (const name of sheets) {
      for (const [selectors, decls] of rules(read(name))) {
        if (!/\.stage-art(?![\w-])/.test(selectors)) continue;
        seen = true;
        expect(decls, `${name}: ${selectors}`).not.toMatch(/z-index|position:\s*(fixed|sticky)/);
      }
    }
    expect(seen).toBe(true);
    expect(rules(read('room.css')).some(([sel, decls]) => sel === '.stage-art' && /pointer-events:\s*none/.test(decls))).toBe(true);
  });

  it('crossfades covers and the backdrop in 600 ms', () => {
    expect(rule('.xfade-in')).toMatch(/animation:\s*fade-in\s+600ms\s+ease\s*;/);
    expect(keyframes('fade-in')).toMatch(/opacity:\s*0/);
  });

  it('moves the equalizer bars only while the room plays', () => {
    expect(rule(".eq[data-playing='true'] i")).toMatch(/animation:\s*eq-bounce\s/);
    expect(allowed).not.toMatch(/\.eq\s+i\s*\{/);
    expect(allowed).not.toMatch(/\.eq\[data-playing='false'\][^{]*\{[^}]*animation/);
  });

  it('pulses the sync dot every 2 s', () => {
    expect(rule('.sync-dot::after')).toMatch(/animation:\s*sync-pulse\s+2s\s/);
  });

  it('turns the connecting ring', () => {
    expect(rule('.status-ring')).toMatch(/animation:\s*status-spin\s+1s\s+linear\s+infinite/);
    expect(keyframes('status-spin')).toMatch(/rotate\(45deg\)[\s\S]*rotate\(405deg\)/);
  });

  it('brings toasts and dialogs in', () => {
    expect(rule('.toast')).toMatch(/animation:\s*toast-in\s+200ms\s+ease-out/);
    expect(rule('.dialog')).toMatch(/animation:\s*dialog-in\s+200ms\s+ease-out/);
  });

  it('eases small state changes', () => {
    expect(allowed).toMatch(/\.btn,\s*\.icon-btn[^{]*\{[^}]*transition:[^}]*150ms/);
    expect(rule(".played-toggle .icon")).toMatch(/transition:\s*transform\s+200ms/);
  });

  it('runs the marquee on the current row by itself and on others on hover or focus, at the measured pace', () => {
    expect(allowed).toMatch(/\.meta-clip\[data-marquee='auto'\]\s*>\s*\.meta-track/);
    expect(allowed).toMatch(/\.row:focus-within\s+\.meta-clip\[data-marquee='hover'\]\s*>\s*\.meta-track/);
    // Hover only where a pointer can hover: a tap on a touch screen leaves :hover stuck on the row.
    const at = allowed.indexOf('@media (hover: hover)');
    expect(at).toBeGreaterThanOrEqual(0);
    const hover = blockAt(allowed, at);
    expect(hover).toMatch(/\.row:hover\s+\.meta-clip\[data-marquee='hover'\]\s*>\s*\.meta-track/);
    expect(hover).toMatch(/\.meta-clip\[data-marquee='hover'\]:hover\s*>\s*\.meta-track/);
    expect(hover).toMatch(/animation:\s*marquee\s/);
    const rest = allowed.slice(0, at) + allowed.slice(allowed.indexOf(hover, at) + hover.length + 1);
    expect(rest.replace(/\/\*[\s\S]*?\*\//g, '')).not.toMatch(/:hover/);
    expect(allowed).not.toMatch(/data-marquee='off'/);
    // One pass takes --marquee-duration (30 px/s, at least 4 s); the cycle adds the pauses and the way back.
    expect(allowed).toMatch(/animation:\s*marquee\s+calc\(var\(--marquee-duration\)\s*\*\s*2\)\s+linear\s+infinite/);
    const frames = keyframes('marquee')!;
    expect(frames).toMatch(/0%,\s*20%\s*\{\s*transform:\s*translateX\(0\)/);
    expect(frames).toMatch(/70%,\s*90%\s*\{\s*transform:\s*translateX\(calc\(-1\s*\*\s*var\(--marquee-distance\)\)\)/);
    expect(frames).toMatch(/100%\s*\{\s*transform:\s*translateX\(0\)/);
    // The text keeps its ellipsis while it rests (it may shrink), and its full width while it moves.
    const resting = keyframes('marquee-rest')!;
    expect(resting).toMatch(/0%,\s*20%\s*\{\s*flex-shrink:\s*1/);
    expect(resting).toMatch(/20\.1%,\s*100%\s*\{\s*flex-shrink:\s*0/);
    expect(allowed).toMatch(/:last-child[^{]*\{\s*animation:\s*marquee-rest\s+calc\(var\(--marquee-duration\)\s*\*\s*2\)\s+linear\s+infinite/);
    expect(allowed).not.toMatch(/:last-child[^{]*\{[^}]*text-overflow:\s*clip/);
  });
});
