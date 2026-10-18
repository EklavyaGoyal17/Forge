import { useMemo, useState } from 'react'
import type { ExplorerDetails, ExplorerStep, ExplorerTrace } from '../lib/api'
import { fmt } from '../lib/format'
import { Badge, Stat, Tabs, cx } from './ui'

const KIND_TONE: Record<string, string> = {
  READ: 'text-info',
  WRITE: 'text-accent',
  CAS: 'text-warn',
  ATOMIC: 'text-ok',
  ACQUIRE: 'text-dim',
  TRY_ACQUIRE: 'text-dim',
  RELEASE: 'text-muted',
}

/**
 * Indices of steps that take part in a lost update: a write to a target by thread A, where the
 * value A last read from that target was overwritten by another thread in between.
 */
export function conflictingSteps(steps: ExplorerStep[]): Set<number> {
  const out = new Set<number>()
  const modifies = (s: ExplorerStep) =>
    s.kind === 'WRITE' || s.kind === 'ATOMIC' || (s.kind === 'CAS' && (s.outcome ?? '').startsWith('succeeded'))
  steps.forEach((w, wi) => {
    if (w.kind !== 'WRITE') return
    let ri = -1
    for (let i = wi - 1; i >= 0; i--) {
      if (steps[i].thread === w.thread && steps[i].target === w.target && steps[i].kind === 'READ') {
        ri = i
        break
      }
    }
    if (ri < 0) return
    const interferers = steps.slice(ri + 1, wi).filter((s) => s.thread !== w.thread && s.target === w.target && modifies(s))
    if (interferers.length > 0) {
      out.add(steps[ri].index)
      interferers.forEach((s) => out.add(s.index))
      out.add(w.index)
    }
  })
  return out
}

export function TraceView({ trace }: { trace: ExplorerTrace }) {
  const threads = trace.threads.length > 0 ? trace.threads : [...new Set(trace.steps.map((s) => s.thread))]
  const conflicts = useMemo(() => conflictingSteps(trace.steps), [trace.steps])
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full border-collapse text-[12.5px]">
          <caption className="sr-only">Interleaving: one row per scheduling step, one column per thread</caption>
          <thead>
            <tr className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
              <th scope="col" className="w-12 px-2 py-1.5 font-medium">step</th>
              {threads.map((t) => <th scope="col" key={t} className="px-2 py-1.5 font-medium">{t}</th>)}
            </tr>
          </thead>
          <tbody>
            {trace.steps.map((s) => (
              <tr key={s.index} className={cx('border-t border-border', conflicts.has(s.index) && 'bg-bad-soft')}>
                <td className="num px-2 py-1 text-muted">{s.index + 1}</td>
                {threads.map((t) => (
                  <td key={t} className="px-2 py-1 align-top">
                    {s.thread === t && (
                      <span className="num">
                        <span className={cx('font-semibold', KIND_TONE[s.kind] ?? 'text-text')}>{s.kind}</span>{' '}
                        <span className="text-dim">{s.detail.replace(/^(read|write|lock|unlock|tryLock|CAS)\s/, '')}</span>
                        {s.outcome && <span className="text-muted"> → {s.outcome}</span>}
                      </span>
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center gap-2 text-[12px] text-dim">
        <Badge tone={trace.executionOutcome === 'COMPLETED' && trace.outcome === 'invariant held' ? 'ok' : 'bad'}>{trace.outcome}</Badge>
        {trace.waitFor.map((w) => <Badge key={w} tone="bad">{w}</Badge>)}
        {conflicts.size > 0 && <span>Highlighted rows: a read whose value was overwritten by another thread before this thread wrote back.</span>}
        {trace.truncated && <span className="text-warn">Showing the first {trace.steps.length} of {trace.totalSteps} steps.</span>}
      </div>
    </div>
  )
}

export function ExplorerView({ details }: { details: ExplorerDetails }) {
  const [which, setWhich] = useState<'counterexample' | 'passing'>(details.counterexample ? 'counterexample' : 'passing')
  const total = details.schedulesExplored
  const outcomes = Object.entries(details.outcomes).sort((a, b) => b[1] - a[1])
  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Schedules explored" value={fmt(total)} sub={details.strategy === 'random' ? 'random sampling' : details.exhaustive ? 'exhaustive' : `budget ${fmt(details.maxSchedules)} reached`} />
        <Stat label="Violating" value={<span className={details.violations > 0 ? 'text-bad' : 'text-ok'}>{fmt(details.violations)}</span>} sub={total > 0 ? `${((100 * details.violations) / total).toFixed(1)}% of schedules` : undefined} />
        <Stat label="Deadlocks" value={fmt(details.deadlocks)} sub={details.stepLimitHits > 0 ? `${details.stepLimitHits} hit step bound` : undefined} />
        <Stat label="Exploration time" value={`${fmt(details.elapsedMs)} ms`} sub={details.errors > 0 ? `${details.errors} errors` : undefined} />
      </div>

      <div>
        <h4 className="mb-2 text-[12px] font-medium uppercase tracking-wider text-muted">Final-state distribution</h4>
        <ul className="flex flex-col gap-1.5">
          {outcomes.map(([label, count]) => (
            <li key={label} className="grid grid-cols-[minmax(0,14rem)_1fr_4rem] items-center gap-3 text-[12.5px]">
              <span className="num truncate text-dim" title={label}>{label}</span>
              <span className="h-2 rounded bg-panel-2">
                <span className="block h-2 rounded" style={{ width: `${(100 * count) / total}%`, background: 'var(--series-1)' }} />
              </span>
              <span className="num text-right text-dim">{count}</span>
            </li>
          ))}
        </ul>
      </div>

      {(details.counterexample || details.passingExample) && (
        <div className="flex flex-col gap-3">
          <Tabs
            label="Example schedules"
            value={which}
            onChange={setWhich}
            tabs={[
              ...(details.counterexample ? [{ id: 'counterexample' as const, label: 'First violating schedule' }] : []),
              ...(details.passingExample ? [{ id: 'passing' as const, label: 'First passing schedule' }] : []),
            ]}
          />
          {which === 'counterexample' && details.counterexample && <TraceView trace={details.counterexample} />}
          {which === 'passing' && details.passingExample && <TraceView trace={details.passingExample} />}
        </div>
      )}
      <p className="text-[12px] text-muted leading-relaxed">
        Granularity: {details.granularity}. Exhaustive results hold only at this granularity; they do not model Java Memory Model reorderings or stale reads.
      </p>
    </div>
  )
}
