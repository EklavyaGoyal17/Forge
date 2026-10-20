import { useState, type ReactNode } from 'react'
import { CartesianGrid, Legend, Line, LineChart, ReferenceArea, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { FailureDetails, FailureSeriesPoint, FailureTrial } from '../lib/api'
import { fmt } from '../lib/format'
import { Stat, cx } from './ui'

/** Contiguous [start, end) time ranges (ms) in which the breaker was open at some point of each window. */
export function breakerOpenRanges(series: FailureSeriesPoint[], windowMs: number): [number, number][] {
  const out: [number, number][] = []
  let start: number | null = null
  for (const p of series) {
    if (p.breaker === 2 && start === null) start = p.tMs
    if (p.breaker !== 2 && start !== null) {
      out.push([start, p.tMs])
      start = null
    }
  }
  if (start !== null && series.length > 0) out.push([start, series[series.length - 1].tMs + windowMs])
  return out
}

/** Per-window rates: attempts and completions per second. */
export function failureRates(series: FailureSeriesPoint[], windowMs: number) {
  const perSecond = 1000 / windowMs
  return series.map((p) => ({ ...p, attemptsPerSec: p.attempts * perSecond, completedPerSec: p.completed * perSecond }))
}

function Timeline({ details, trial }: { details: FailureDetails; trial: FailureTrial }) {
  const c = details.config
  const data = failureRates(trial.series, c.windowMs)
  const open = breakerOpenRanges(trial.series, c.windowMs)
  const faulty = c.fault !== 'none'
  const recoveryAt = trial.recoveryMs != null ? c.faultEndMs + trial.recoveryMs : null
  const refs = (
    [
      c.measureStartMs > 0 && <ReferenceArea key="warm" x1={0} x2={c.measureStartMs} fill="var(--muted)" fillOpacity={0.08} />,
      faulty && <ReferenceArea key="fault" x1={c.faultStartMs} x2={c.faultEndMs} fill="var(--bad)" fillOpacity={0.12} />,
      ...open.map(([a, b]) => <ReferenceArea key={`open-${a}`} x1={a} x2={b} fill="var(--warn)" fillOpacity={0.16} />),
      recoveryAt != null && <ReferenceLine key="rec" x={recoveryAt} stroke="var(--ok)" strokeDasharray="4 3" />,
    ].filter(Boolean)
  )
  const axis = <XAxis dataKey="tMs" type="number" domain={[0, 'dataMax']} tickFormatter={(v) => `${(v / 1000).toFixed(1)}s`} stroke="var(--muted)" fontSize={11} />
  const panel = (title: string, children: ReactNode, unit: string) => (
    <div>
      <h5 className="mb-1 text-[11px] font-medium uppercase tracking-wider text-muted">{title}</h5>
      <div className="h-36">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 4, right: 16, bottom: 0, left: 4 }} syncId={`failure-${trial.trial}`}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="2 4" />
            {refs}
            {axis}
            <YAxis stroke="var(--muted)" fontSize={11} width={48} unit={unit} />
            <Tooltip contentStyle={{ background: 'var(--panel)', border: '1px solid var(--border)', fontSize: 12 }}
                     labelFormatter={(v) => `t = ${fmt(Number(v))} ms`} formatter={(v) => (typeof v === 'number' ? fmt(v) : String(v))} />
            {children}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
  return (
    <div className="flex flex-col gap-3">
      {panel('Success per window (by completion time)', <Line type="stepAfter" dataKey="successPercent" name="success %" stroke="var(--ok)" dot={false} isAnimationActive={false} connectNulls={false} />, '%')}
      {panel('Caller latency (requests intended in the window)', <>
        <Line type="linear" dataKey="p50Ms" name="p50" stroke="var(--series-1)" dot={false} isAnimationActive={false} connectNulls={false} />
        <Line type="linear" dataKey="p99Ms" name="p99" stroke="var(--series-2)" dot={false} isAnimationActive={false} connectNulls={false} />
        <Legend wrapperStyle={{ fontSize: 11 }} />
      </>, 'ms')}
      {panel('Load on the dependency', <>
        <Line type="stepAfter" dataKey="attemptsPerSec" name="attempts/s sent" stroke="var(--series-3)" dot={false} isAnimationActive={false} />
        <Line type="stepAfter" dataKey="backlog" name="backlog (queued + in service)" stroke="var(--series-4)" dot={false} isAnimationActive={false} />
        <Legend wrapperStyle={{ fontSize: 11 }} />
      </>, '')}
      <p className="text-[11.5px] text-muted">
        Shaded: <span className="text-dim">grey</span> warm-up, <span className="text-bad">red</span> fault active
        {open.length > 0 && <>, <span className="text-warn">amber</span> breaker open</>}
        {recoveryAt != null && <>; <span className="text-ok">dashed green</span> recovered</>}.
        Window {fmt(c.windowMs)} ms.
      </p>
    </div>
  )
}

function Accounting({ trial }: { trial: FailureTrial }) {
  const rows: [string, number, string][] = [
    ['Caller requests measured', trial.offered, 'intended during the sustained phase'],
    ['Succeeded (HTTP 200)', trial.ok, ''],
    ['Error status', trial.httpErrors, 'last attempt answered 500, or 503 when short-circuited'],
    ['Timed out', trial.timeouts, 'no success before the request deadline'],
    ['Transport failures', trial.failures, 'refused or dropped connection on the last attempt'],
    ['Attempts sent by the policy', trial.attempts, 'whole trial, including warm-up'],
    ['Retries', trial.retries, ''],
    ['Short-circuited by the breaker', trial.shortCircuited, ''],
    ['Retries refused by the budget', trial.budgetDenied, ''],
    ['Received by the dependency', trial.downstream.received, ''],
    ['… finished after the caller gave up', trial.downstream.wasted, 'work done for nobody'],
    ['Received − sent', trial.receivedMinusSent, 'positive: the HTTP client resent requests itself; negative: attempts that never arrived (refused)'],
  ]
  return (
    <table className="w-full border-collapse text-[12.5px]">
      <caption className="sr-only">Request accounting for this trial</caption>
      <tbody>
        {rows.map(([label, value, note]) => (
          <tr key={label} className="border-t border-border first:border-t-0">
            <th scope="row" className="py-1 pr-3 text-left font-normal text-dim">{label}</th>
            <td className="num py-1 pr-3 text-right">{fmt(value)}</td>
            <td className="py-1 text-[11.5px] text-muted">{note}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function FailureView({ details }: { details: FailureDetails }) {
  const [index, setIndex] = useState(0)
  const trial = details.trials[Math.min(index, details.trials.length - 1)]
  if (!trial) return <p className="text-[13px] text-muted">No trials recorded.</p>
  const c = details.config
  const success = trial.offered === 0 ? 0 : (100 * trial.ok) / trial.offered
  const measuredAttempts = trial.series.filter((p) => p.tMs >= c.measureStartMs && p.tMs < c.measureEndMs).reduce((n, p) => n + p.attempts, 0)
  const amplification = trial.offered === 0 ? 0 : measuredAttempts / trial.offered
  const peak = Math.max(0, ...trial.series.map((p) => p.backlog))
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2 text-[12.5px] text-dim">
        <span>Fault <b className="text-text">{c.fault}</b> from {fmt(c.faultStartMs)} to {fmt(c.faultEndMs)} ms</span>
        <span>· {fmt(c.rate)} req/s offered</span>
        {c.healthyCapacityRps != null && <span>· dependency capacity {fmt(c.healthyCapacityRps)} req/s ({c.downstreamWorkers} workers ÷ {fmt(c.downstreamLatencyMs)} ms)</span>}
        <span>· bound to {c.bindAddress}</span>
      </div>
      {details.trials.length > 1 && (
        <div role="group" aria-label="Trial" className="flex flex-wrap gap-1">
          {details.trials.map((t, i) => (
            <button key={t.trial} type="button" onClick={() => setIndex(i)} aria-pressed={i === index}
                    className={cx('h-7 rounded-md border px-2 text-[12px]', i === index ? 'border-accent text-text' : 'border-border text-dim')}>
              trial {t.trial + 1}{t.recoveryMs == null && c.fault !== 'none' ? ' · no recovery' : ''}
            </button>
          ))}
        </div>
      )}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
        <Stat label="Success" value={`${success.toFixed(1)}%`} sub={`${fmt(trial.ok)} of ${fmt(trial.offered)}`} />
        <Stat label="Recovery" value={c.fault === 'none' ? '—' : trial.recoveryMs == null ? <span className="text-bad">none</span> : `${fmt(trial.recoveryMs)} ms`}
              sub={c.fault === 'none' ? 'control run' : trial.recoveryMs == null ? 'still failing when load ended' : 'after the fault ended'} />
        <Stat label="Attempts per request" value={amplification.toFixed(2)} sub={`sustained phase; ${fmt(trial.retries)} retries in all`} />
        <Stat label="Peak backlog" value={fmt(peak)} sub="at the dependency" />
        <Stat label="Breaker" value={details.policy.breaker ? `${trial.breakerTransitions.filter((t) => t.state === 'OPEN').length}× open` : 'none'}
              sub={details.policy.breaker ? `${fmt(trial.shortCircuited)} short-circuited` : undefined} />
      </div>
      <Timeline details={details} trial={trial} />
      <details className="rounded-md border border-border px-3 py-2">
        <summary className="cursor-pointer text-[12.5px] text-dim">Request accounting</summary>
        <div className="mt-2"><Accounting trial={trial} /></div>
      </details>
    </div>
  )
}
