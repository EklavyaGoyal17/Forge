import { useState } from 'react'
import { CartesianGrid, Legend, Line, LineChart, ReferenceArea, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { HttpLoadDetails, HttpLoadTrial, LatencyKind, LatencySummary, LoadSeriesPoint, WorkerThreadProfile } from '../lib/api'
import { fmt } from '../lib/format'
import { cx } from './ui'

const KINDS: LatencyKind[] = ['client', 'uncorrected', 'service', 'queueWait']

export const KIND_LABEL: Record<LatencyKind, string> = {
  client: 'Client, from intended send',
  uncorrected: 'Client, from actual send',
  service: 'Server service time',
  queueWait: 'Server queue wait',
}

const KIND_COLOR: Record<LatencyKind, string> = {
  client: 'var(--series-1)',
  uncorrected: 'var(--series-2)',
  service: 'var(--series-3)',
  queueWait: 'var(--series-4)',
}

const tooltipStyle = { background: 'var(--panel)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 12 }
const axisTick = { fill: 'var(--muted)', fontSize: 11 }

/** Position of percentile p on a "nines" axis: 1 / (1 − p). p50 → 2, p99 → 100, p99.9 → 1000. */
export function ninesX(p: number): number {
  return 1 / (1 - p / 100)
}

function ninesLabel(x: number): string {
  const p = 100 * (1 - 1 / x)
  return `p${Number(p.toFixed(3))}`
}

export type SpectrumRow = { x: number; percentile: number } & Partial<Record<LatencyKind, number>>

/** One row per percentile across the four latency kinds; only percentiles the sample can resolve. */
export function spectrumRows(details: HttpLoadDetails): SpectrumRow[] {
  const rows = new Map<number, SpectrumRow>()
  for (const kind of KINDS) {
    for (const point of details.aggregate.spectrum[kind] ?? []) {
      if (!point.resolvable) continue
      const row = rows.get(point.percentile) ?? { x: ninesX(point.percentile), percentile: point.percentile }
      row[kind] = point.valueMs
      rows.set(point.percentile, row)
    }
  }
  return [...rows.values()].sort((a, b) => a.x - b.x)
}

/** Per-window counts converted to rates (req/s). */
export function rateSeries(points: LoadSeriesPoint[], windowMs: number) {
  const perSecond = 1000 / windowMs
  return points.map((p) => ({ tMs: p.tMs, offered: p.offered * perSecond, completed: p.completed * perSecond, errors: p.errors * perSecond }))
}

/** p99.9 only when at least 1000 values were recorded; otherwise it is the maximum in disguise. */
function p999(s: LatencySummary): number | undefined {
  return s.count >= 1000 ? s.p999Ms : undefined
}

/** Values at or above the request timeout are lower bounds: those requests were recorded when the client gave up. */
function Latency({ value, timeoutMs, strong }: { value: number | undefined; timeoutMs: number; strong?: boolean }) {
  const bound = value !== undefined && value >= timeoutMs
  return (
    <td className={cx('num px-2.5 py-1.5', strong && 'font-medium', bound && 'text-warn')} title={bound ? 'At the request timeout: a lower bound of the real latency' : undefined}>
      {bound ? '≥ ' : ''}{fmt(value)}
    </td>
  )
}

function errorCount(t: HttpLoadTrial) {
  return t.httpErrors + t.failures + t.timeouts + t.unfinished
}

export function HttpLoadView({ details }: { details: HttpLoadDetails }) {
  const [selected, setSelected] = useState(0)
  const c = details.config
  const trial = details.trials[Math.min(selected, details.trials.length - 1)]
  const totalOffered = details.trials.reduce((n, t) => n + t.offered, 0)
  const totalErrors = details.trials.reduce((n, t) => n + errorCount(t), 0)
  const workers = details.workers === 0 ? 'virtual threads (no pool)' : `${details.workers} worker threads`

  return (
    <section aria-label="HTTP load results" className="flex flex-col gap-5">
      <div className="flex flex-col gap-1 text-[13px]">
        <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">Open-model load</h4>
        <p className="text-dim">
          <span className="num text-text">{fmt(c.rate)} req/s</span> offered on a fixed schedule for {fmt(c.durationMs)} ms after a {fmt(c.warmupMs)} ms
          warm-up, per trial · {workers} · downstream {fmt(c.downstreamMs)} ms · CPU work {fmt(c.cpuWorkUs)} µs · ≤ {fmt(c.connections)} connections ·
          timeout {fmt(c.requestTimeoutMs)} ms
        </p>
        <p className="text-[12px] text-muted">
          Values marked ≥ reached the request timeout and are lower bounds. Target service bound to {details.bindAddress} on an ephemeral port inside the runner JVM. Latencies are HdrHistogram values
          ({details.histogram.significantDigits} significant digits).
        </p>
      </div>

      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full text-[12.5px]">
          <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">
            Client latency (ms), measured from each request's <em>intended</em> send time
          </caption>
          <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
            <tr>
              {['Trial', 'Requests', 'p50', 'p90', 'p95', 'p99', 'p99.9', 'max', 'Achieved req/s', 'Errors'].map((h) => (
                <th key={h} scope="col" className="px-2.5 py-1.5 font-medium">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {details.trials.map((t) => (
              <tr key={t.trial} className="border-t border-border">
                <th scope="row" className="num px-2.5 py-1.5 text-left font-normal text-muted">{t.trial + 1}</th>
                <td className="num px-2.5 py-1.5 text-muted">{fmt(t.offered)}</td>
                <Latency value={t.client.p50Ms} timeoutMs={c.requestTimeoutMs} />
                <Latency value={t.client.p90Ms} timeoutMs={c.requestTimeoutMs} />
                <Latency value={t.client.p95Ms} timeoutMs={c.requestTimeoutMs} />
                <Latency value={t.client.p99Ms} timeoutMs={c.requestTimeoutMs} strong />
                <td className="num px-2.5 py-1.5" title={p999(t.client) === undefined ? `not resolvable from ${t.client.count} values` : undefined}>{fmt(p999(t.client))}</td>
                <Latency value={t.client.maxMs} timeoutMs={c.requestTimeoutMs} />
                <td className="num px-2.5 py-1.5">{fmt(t.achievedRps)}</td>
                <td className={cx('num px-2.5 py-1.5', errorCount(t) > 0 && 'text-bad')}>
                  {errorCount(t) === 0 ? '0' : `${fmt(errorCount(t))} (${((100 * errorCount(t)) / Math.max(1, t.offered)).toFixed(2)}%)`}
                </td>
              </tr>
            ))}
            <tr className="border-t-2 border-border-strong bg-panel-2/40">
              <th scope="row" className="px-2.5 py-1.5 text-left font-normal text-dim">All trials (merged histograms)</th>
              <td className="num px-2.5 py-1.5 text-muted">{fmt(details.aggregate.client.count)}</td>
              <Latency value={details.aggregate.client.p50Ms} timeoutMs={c.requestTimeoutMs} />
              <Latency value={details.aggregate.client.p90Ms} timeoutMs={c.requestTimeoutMs} />
              <Latency value={details.aggregate.client.p95Ms} timeoutMs={c.requestTimeoutMs} />
              <Latency value={details.aggregate.client.p99Ms} timeoutMs={c.requestTimeoutMs} strong />
              <Latency value={p999(details.aggregate.client)} timeoutMs={c.requestTimeoutMs} />
              <Latency value={details.aggregate.client.maxMs} timeoutMs={c.requestTimeoutMs} />
              <td className="num px-2.5 py-1.5 text-muted">—</td>
              <td className={cx('num px-2.5 py-1.5', totalErrors > 0 && 'text-bad')}>
                {totalErrors === 0 ? '0' : `${fmt(totalErrors)} of ${fmt(totalOffered)}`}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full text-[12.5px]">
          <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">Where the time went (ms unless stated), per trial</caption>
          <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
            <tr>
              {['Trial', 'p99 from actual send', 'Queue wait p50', 'Queue wait p99', 'Service p50', 'Service p99', 'Worker time / request', "Little's-law capacity (req/s)", 'Generator timer lag p99'].map((h) => (
                <th key={h} scope="col" className="px-2.5 py-1.5 font-medium">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {details.trials.map((t) => (
              <tr key={t.trial} className="border-t border-border">
                <th scope="row" className="num px-2.5 py-1.5 text-left font-normal text-muted">{t.trial + 1}</th>
                <td className="num px-2.5 py-1.5">{fmt(t.uncorrected.p99Ms)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.queueWait.p50Ms)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.queueWait.p99Ms)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.service.p50Ms)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.service.p99Ms)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.occupancyMs)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.littleBoundRps)}</td>
                <td className="num px-2.5 py-1.5">{fmt(t.timerLag.p99Ms)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {trial && (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">Over time</h4>
            {details.trials.length > 1 && (
              <>
                <label htmlFor="load-trial" className="text-[12px] text-muted">Trial</label>
                <select id="load-trial" value={selected} onChange={(e) => setSelected(Number(e.target.value))} className="h-7 rounded-md border border-border bg-bg-elev px-2 text-[12.5px]">
                  {details.trials.map((t, i) => <option key={t.trial} value={i}>{t.trial + 1}</option>)}
                </select>
              </>
            )}
            <span className="text-[12px] text-muted">
              {fmt(c.windowMs)} ms windows; latency is grouped by intended send time, completions by completion time. Shaded: warm-up (not measured).
            </span>
          </div>
          <LatencyOverTime trial={trial} />
          <RateOverTime trial={trial} windowMs={c.windowMs} />
          {trial.workerThreads && <WorkerThreads profile={trial.workerThreads} workers={details.workers} />}
        </div>
      )}

      <PercentileSpectrum details={details} />
    </section>
  )
}

/** What the pool's worker threads were doing, sampled on the wall clock (so waiting threads count too). */
export function WorkerThreads({ profile, workers }: { profile: WorkerThreadProfile; workers: number }) {
  const states = Object.entries(profile.states)
  return (
    <div className="grid gap-4 rounded-md border border-border p-3 md:grid-cols-2">
      <div>
        <h5 className="mb-1 text-[11px] font-medium uppercase tracking-wider text-muted">Worker thread states</h5>
        <p className="mb-2 text-[12px] text-muted">
          {fmt(profile.threadSamples)} samples of {workers} workers, every {fmt(profile.periodMs)} ms during the sustained phase. Unlike CPU samples,
          these include threads that are waiting. <b className="text-text">{(100 * profile.blockedShare).toFixed(1)}%</b> blocked inside a request,{' '}
          <b className="text-text">{(100 * profile.idleShare).toFixed(1)}%</b> idle waiting for one.
        </p>
        <ul className="flex flex-col gap-1">
          {states.map(([s, share]) => (
            <li key={s} className="grid grid-cols-[8.5rem_1fr_3.5rem] items-center gap-2 text-[12.5px]">
              <span className="num text-dim">{s}</span>
              <span className="h-2 rounded bg-panel-2"><span className="block h-2 rounded" style={{ width: `${100 * share}%`, background: s === 'RUNNABLE' ? 'var(--series-1)' : 'var(--series-2)' }} /></span>
              <span className="num text-right">{(100 * share).toFixed(1)}%</span>
            </li>
          ))}
        </ul>
      </div>
      <div>
        <h5 className="mb-1 text-[11px] font-medium uppercase tracking-wider text-muted">Where they were</h5>
        <table className="w-full text-[12.5px]">
          <caption className="sr-only">Most frequent top frames of worker threads</caption>
          <tbody>
            {profile.topFrames.map((f) => (
              <tr key={f.frame} className="border-t border-border first:border-t-0">
                <td className="num py-1 pr-2 text-dim">{f.frame}</td>
                <td className="num py-1 text-right">{(100 * f.share).toFixed(1)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function TimeAxis() {
  return <XAxis dataKey="tMs" type="number" domain={[0, 'dataMax']} tick={axisTick} stroke="var(--border)" tickFormatter={(v: number) => `${fmt(v / 1000, 3)} s`} />
}

function LatencyOverTime({ trial }: { trial: HttpLoadTrial }) {
  if (trial.series.length === 0) return <p className="text-[13px] text-muted">No time series recorded.</p>
  return (
    <figure aria-label={`Client latency over time, trial ${trial.trial + 1}`} className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={trial.series} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
          <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
          {trial.measureStartMs > 0 && <ReferenceArea x1={0} x2={trial.measureStartMs} fill="var(--muted)" fillOpacity={0.08} />}
          <ReferenceLine x={trial.measureEndMs} stroke="var(--border-strong)" strokeDasharray="4 4" label={{ value: 'end of schedule', fill: 'var(--muted)', fontSize: 10, position: 'insideTopRight' }} />
          {TimeAxis()}
          <YAxis tick={axisTick} stroke="var(--border)" width={64} tickFormatter={(v: number) => fmt(v, 3)} label={{ value: 'ms', angle: -90, position: 'insideLeft', fill: 'var(--muted)', fontSize: 11 }} />
          <Tooltip contentStyle={tooltipStyle} labelStyle={{ color: 'var(--muted)' }} labelFormatter={(l) => `t = ${fmt(Number(l) / 1000, 3)} s`} formatter={(v: number, name: string) => [`${fmt(v)} ms`, name]} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line type="linear" dataKey="p50Ms" name="p50" stroke="var(--series-1)" dot={false} isAnimationActive={false} connectNulls={false} />
          <Line type="linear" dataKey="p99Ms" name="p99" stroke="var(--series-2)" dot={false} isAnimationActive={false} connectNulls={false} />
          <Line type="linear" dataKey="maxMs" name="max" stroke="var(--bad)" strokeOpacity={0.6} dot={false} isAnimationActive={false} connectNulls={false} />
        </LineChart>
      </ResponsiveContainer>
    </figure>
  )
}

function RateOverTime({ trial, windowMs }: { trial: HttpLoadTrial; windowMs: number }) {
  const data = rateSeries(trial.series, windowMs)
  if (data.length === 0) return null
  return (
    <figure aria-label={`Offered and completed requests per second over time, trial ${trial.trial + 1}`} className="h-44 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
          <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
          {trial.measureStartMs > 0 && <ReferenceArea x1={0} x2={trial.measureStartMs} fill="var(--muted)" fillOpacity={0.08} />}
          {TimeAxis()}
          <YAxis tick={axisTick} stroke="var(--border)" width={64} tickFormatter={(v: number) => fmt(v, 3)} label={{ value: 'req/s', angle: -90, position: 'insideLeft', fill: 'var(--muted)', fontSize: 11 }} />
          <Tooltip contentStyle={tooltipStyle} labelStyle={{ color: 'var(--muted)' }} labelFormatter={(l) => `t = ${fmt(Number(l) / 1000, 3)} s`} formatter={(v: number, name: string) => [`${fmt(v)} req/s`, name]} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line type="stepAfter" dataKey="offered" name="offered (by intended time)" stroke="var(--muted)" dot={false} isAnimationActive={false} />
          <Line type="stepAfter" dataKey="completed" name="completed OK" stroke="var(--ok)" dot={false} isAnimationActive={false} />
          <Line type="stepAfter" dataKey="errors" name="errors" stroke="var(--bad)" dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </figure>
  )
}

function PercentileSpectrum({ details }: { details: HttpLoadDetails }) {
  const rows = spectrumRows(details)
  if (rows.length === 0) return null
  const ticks = [1, 2, 10, 100, 1000, 10000].filter((t) => t <= rows[rows.length - 1].x)
  return (
    <div className="flex flex-col gap-1">
      <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">Percentile spectrum, all measured trials</h4>
      <figure aria-label="Latency by percentile for client, uncorrected client, server service time and server queue wait" className="h-64 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
            <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
            <XAxis dataKey="x" type="number" scale="log" domain={[1, 'dataMax']} ticks={ticks} tick={axisTick} stroke="var(--border)" tickFormatter={ninesLabel} />
            <YAxis tick={axisTick} stroke="var(--border)" width={64} tickFormatter={(v: number) => fmt(v, 3)} label={{ value: 'ms', angle: -90, position: 'insideLeft', fill: 'var(--muted)', fontSize: 11 }} />
            <Tooltip contentStyle={tooltipStyle} labelStyle={{ color: 'var(--muted)' }} labelFormatter={(l) => ninesLabel(Number(l))} formatter={(v: number, name: string) => [`${fmt(v)} ms`, name]} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            {KINDS.map((k) => (
              <Line key={k} type="linear" dataKey={k} name={KIND_LABEL[k]} stroke={KIND_COLOR[k]} dot={{ r: 2.5 }} isAnimationActive={false} connectNulls={false} />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </figure>
      <p className="text-[11px] text-muted">
        Computed from the merged HdrHistograms of this run. Percentiles the sample cannot resolve (fewer than 1/(1−p) values) are not plotted.
        The gap between the two client lines is time requests waited in the load generator before they could be sent.
      </p>
    </div>
  )
}
