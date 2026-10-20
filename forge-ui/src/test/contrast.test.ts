import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * WCAG 2.1 contrast of the design tokens, computed from index.css for both themes. Every token used
 * for text must reach 4.5:1 (AA, normal text) on every surface, and button text on the accent colour
 * too. jsdom cannot measure rendered contrast, so the tokens are checked directly.
 */
const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8')

function tokens(selector: string): Record<string, string> {
  const start = css.indexOf(selector)
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
  const out: Record<string, string> = {}
  for (const m of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})/g)) out[m[1]] = m[2]
  return out
}

function luminance(hex: string) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}

export function contrast(a: string, b: string) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const TEXT = ['text', 'text-dim', 'muted', 'accent', 'ok', 'bad', 'warn', 'info']
const SURFACES = ['bg', 'bg-elev', 'panel', 'panel-2']

describe('design-token contrast (WCAG 2.1 AA)', () => {
  it('computes the reference ratios correctly', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5)
    expect(contrast('#767676', '#ffffff')).toBeCloseTo(4.54, 2)
  })

  for (const [theme, selector] of [['dark', ':root.dark'], ['light', ':root.light']] as const) {
    it(`${theme}: every text token reaches 4.5:1 on every surface`, () => {
      const t = tokens(selector)
      const failures: string[] = []
      for (const fg of TEXT) {
        for (const bg of SURFACES) {
          const r = contrast(t[fg], t[bg])
          if (r < 4.5) failures.push(`${fg} on ${bg}: ${r.toFixed(2)}`)
        }
      }
      expect(failures).toEqual([])
    })

    it(`${theme}: button text on the accent colour reaches 4.5:1`, () => {
      const t = tokens(selector)
      expect(contrast(t['accent-ink'], t.accent)).toBeGreaterThanOrEqual(4.5)
    })
  }
})
