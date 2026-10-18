import { describe, expect, it } from 'vitest'
import { bytes, duration, fmt, fmtUnit, pct, relativeTime, signedPct } from '../lib/format'

describe('format', () => {
  it('formats numbers honestly', () => {
    expect(fmt(800000)).toBe('800,000')
    expect(fmt(13.35612)).toBe('13.356')
    expect(fmt(0.0012345)).toBe('0.001234')
    expect(fmt(0)).toBe('0')
    expect(fmt('NaN')).toBe('—')
    expect(fmt(null)).toBe('—')
  })

  it('appends units', () => {
    expect(fmtUnit(12.5, 'ms')).toBe('12.5 ms')
    expect(fmtUnit(3, '%')).toBe('3%')
    expect(fmtUnit('NaN', 'ms')).toBe('—')
  })

  it('formats percentages and durations', () => {
    expect(pct(0.1234)).toBe('12.3%')
    expect(signedPct(5)).toBe('+5.0%')
    expect(signedPct(-99.94)).toBe('-99.9%')
    expect(duration(640)).toBe('640 ms')
    expect(duration(2_060)).toBe('2.1 s') // 2.05 would round to 2.0: binary floating point
    expect(duration(125_000)).toBe('2m 5s')
    expect(bytes(268_435_456)).toBe('256.0 MB')
  })

  it('formats relative time', () => {
    const now = Date.parse('2026-10-19T12:00:00Z')
    expect(relativeTime('2026-10-19T11:59:50Z', now)).toBe('just now')
    expect(relativeTime('2026-10-19T11:48:00Z', now)).toBe('12 min ago')
    expect(relativeTime('2026-10-19T09:00:00Z', now)).toBe('3 h ago')
    expect(relativeTime(null, now)).toBe('—')
  })
})
