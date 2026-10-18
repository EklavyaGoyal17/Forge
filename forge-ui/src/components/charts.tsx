import { CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { ComparisonResult, Trial } from '../lib/api'
import { fmt, toNumber } from '../lib/format'

/** Per-trial values of one metric, in execution order. Red points are trials that violated the invariant. */
export function TrialChart({ trials, metric, unit, median }: { trials: Trial[]; metric: string; unit?: string; median?: number }) {
  const data = trials
    .map((t) => ({ trial: t.index + 1, value: toNumber(t.metrics[metric]), held: t.invariantHeld }))
    .filter((d) => Number.isFinite(d.value))
  if (data.length === 0) return <p className="text-muted text-[13px]">No values for this metric.</p>
  return (
    <figure aria-label={`${metric} per trial`} className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
          <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
          <XAxis dataKey="trial" tick={{ fill: 'var(--muted)', fontSize: 11 }} stroke="var(--border)" label={{ value: 'trial', position: 'insideBottomRight', offset: -2, fill: 'var(--muted)', fontSize: 11 }} />
          <YAxis tick={{ fill: 'var(--muted)', fontSize: 11 }} stroke="var(--border)" width={64} tickFormatter={(v) => fmt(v, 3)} domain={['auto', 'auto']} />
          <Tooltip
            contentStyle={{ background: 'var(--panel)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 12 }}
            labelStyle={{ color: 'var(--muted)' }}
            formatter={(v: number) => [`${fmt(v)}${unit ? ` ${unit}` : ''}`, metric]}
            labelFormatter={(l) => `trial ${l}`}
          />
          {median !== undefined && Number.isFinite(median) && (
            <ReferenceLine y={median} stroke="var(--accent)" strokeDasharray="4 4" label={{ value: `median ${fmt(median)}`, fill: 'var(--accent)', fontSize: 11, position: 'insideTopRight' }} />
          )}
          <Line
            type="linear"
            dataKey="value"
            stroke="var(--series-1)"
            strokeOpacity={0.35}
            isAnimationActive={false}
            dot={(p: { cx?: number; cy?: number; payload?: { held: boolean | null }; index?: number }) => (
              <circle key={p.index} cx={p.cx} cy={p.cy} r={3.5} fill={p.payload?.held === false ? 'var(--bad)' : 'var(--series-1)'} />
            )}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </figure>
  )
}

/** Strip plot of two samples on a shared axis, with medians marked. Every dot is one recorded trial. */
export function StripPlot({ baseline, candidate, labels, unit }: { baseline: number[]; candidate: number[]; labels: [string, string]; unit?: string }) {
  const all = [...baseline, ...candidate].filter(Number.isFinite)
  if (all.length === 0) return null
  let lo = Math.min(...all)
  let hi = Math.max(...all)
  if (lo === hi) {
    lo -= 1
    hi += 1
  }
  const pad = (hi - lo) * 0.06
  const nonNegative = lo >= 0
  lo -= pad
  hi += pad
  if (nonNegative) lo = Math.max(0, lo)
  const W = 640
  const H = 132
  const left = 96
  const right = 16
  const x = (v: number) => left + ((v - lo) / (hi - lo)) * (W - left - right)
  const rows: [number[], string, string, number][] = [
    [baseline, labels[0], 'var(--series-1)', 38],
    [candidate, labels[1], 'var(--series-2)', 84],
  ]
  const ticks = Array.from({ length: 5 }, (_, i) => lo + ((hi - lo) * i) / 4)
  const median = (v: number[]) => {
    const s = [...v].sort((a, b) => a - b)
    const h = (s.length - 1) / 2
    return s.length === 0 ? NaN : (s[Math.floor(h)] + s[Math.ceil(h)]) / 2
  }
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-label={`Trial values: ${labels[0]} vs ${labels[1]}`}>
      {ticks.map((t, i) => (
        <g key={i}>
          <line x1={x(t)} x2={x(t)} y1={16} y2={H - 22} stroke="var(--grid)" />
          <text x={x(t)} y={H - 6} textAnchor="middle" fontSize={10} fill="var(--muted)">{fmt(t, 3)}</text>
        </g>
      ))}
      {rows.map(([vals, label, color, y]) => {
        const m = median(vals)
        return (
          <g key={label}>
            <text x={left - 10} y={y + 4} textAnchor="end" fontSize={11} fill="var(--text-dim)">{label}</text>
            {vals.map((v, i) => (
              <circle key={i} cx={x(v)} cy={y + ((i * 7919) % 13) - 6} r={3.2} fill={color} fillOpacity={0.7}>
                <title>{`${label}: ${fmt(v)}${unit ? ` ${unit}` : ''}`}</title>
              </circle>
            ))}
            {Number.isFinite(m) && <line x1={x(m)} x2={x(m)} y1={y - 14} y2={y + 14} stroke={color} strokeWidth={2.5} />}
          </g>
        )
      })}
    </svg>
  )
}

/**
 * Ratio of medians (candidate / baseline) with its 95% bootstrap interval, on a log-scaled axis
 * centred on 1.0 (no change).
 */
export function RatioInterval({ result }: { result: ComparisonResult }) {
  const est = toNumber(result.ratioOfMedians)
  const low = toNumber(result.ciLow)
  const high = toNumber(result.ciHigh)
  if (![est, low, high].every((v) => Number.isFinite(v) && v > 0)) {
    return <span className="text-muted text-[12px]">interval undefined</span>
  }
  const span = Math.max(Math.abs(Math.log(low)), Math.abs(Math.log(high)), Math.log(1.05)) * 1.15
  const W = 220
  const H = 26
  const x = (v: number) => W / 2 + (Math.log(v) / span) * (W / 2 - 6)
  const color = result.verdict === 'IMPROVEMENT' ? 'var(--ok)' : result.verdict === 'REGRESSION' ? 'var(--bad)' : 'var(--muted)'
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-[220px] h-[26px]" role="img"
      aria-label={`ratio ${fmt(est)}, 95% interval ${fmt(low)} to ${fmt(high)}`}>
      <line x1={0} x2={W} y1={H / 2} y2={H / 2} stroke="var(--grid)" />
      <line x1={x(1)} x2={x(1)} y1={2} y2={H - 2} stroke="var(--border-strong)" strokeDasharray="2 2" />
      <line x1={x(low)} x2={x(high)} y1={H / 2} y2={H / 2} stroke={color} strokeWidth={3} strokeLinecap="round" />
      <circle cx={x(est)} cy={H / 2} r={4.5} fill={color} stroke="var(--panel)" strokeWidth={1.5} />
    </svg>
  )
}
