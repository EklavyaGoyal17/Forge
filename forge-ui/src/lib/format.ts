import type { Num } from './api'

export function toNumber(v: Num | null | undefined): number {
  if (v === null || v === undefined || v === 'NaN') return Number.NaN
  return v
}

/** Compact, honest number formatting: integers stay integers, everything else keeps 3–4 significant digits. */
export function fmt(v: Num | null | undefined, digits = 4): string {
  const n = toNumber(v)
  if (!Number.isFinite(n)) return '—'
  if (Number.isInteger(n) && Math.abs(n) < 1e15) return n.toLocaleString('en-US')
  const a = Math.abs(n)
  if (a >= 1e6) return n.toLocaleString('en-US', { maximumFractionDigits: 0 })
  if (a >= 100) return n.toLocaleString('en-US', { maximumFractionDigits: 1 })
  if (a >= 1) return n.toLocaleString('en-US', { maximumFractionDigits: 3 })
  if (a === 0) return '0'
  return n.toPrecision(digits)
}

export function fmtUnit(v: Num | null | undefined, unit?: string | null): string {
  const s = fmt(v)
  if (s === '—' || !unit) return s
  return unit === '%' ? `${s}%` : `${s} ${unit}`
}

export function pct(v: Num | null | undefined, digits = 1): string {
  const n = toNumber(v)
  return Number.isFinite(n) ? `${(n * 100).toFixed(digits)}%` : '—'
}

export function signedPct(v: Num | null | undefined): string {
  const n = toNumber(v)
  if (!Number.isFinite(n)) return '—'
  return `${n > 0 ? '+' : ''}${n.toFixed(1)}%`
}

export function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  const m = Math.floor(ms / 60_000)
  return `${m}m ${Math.round((ms % 60_000) / 1000)}s`
}

export function runDuration(startedAt: string | null, finishedAt: string | null): number | null {
  if (!startedAt || !finishedAt) return null
  return new Date(finishedAt).getTime() - new Date(startedAt).getTime()
}

export function relativeTime(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—'
  const diff = (now - new Date(iso).getTime()) / 1000
  if (diff < 45) return 'just now'
  if (diff < 3600) return `${Math.round(diff / 60)} min ago`
  if (diff < 86_400) return `${Math.round(diff / 3600)} h ago`
  if (diff < 86_400 * 30) return `${Math.round(diff / 86_400)} d ago`
  return new Date(iso).toLocaleDateString()
}

export function bytes(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 ** 3) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 ** 3).toFixed(1)} GB`
}

export function titleCase(s: string): string {
  return s.toLowerCase().replace(/(^|[\s_-])\w/g, (m) => m.toUpperCase()).replace(/_/g, ' ')
}
