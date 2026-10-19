import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText, Pin, PinOff, Square } from 'lucide-react'
import { useState } from 'react'
import type { MetricSpec, RunView, ScenarioDescriptor, Summary } from '../lib/api'
import { api } from '../lib/api'
import { bytes, duration, fmt, pct, runDuration, toNumber } from '../lib/format'
import { TrialChart } from './charts'
import { ExplorerView } from './ExplorerView'
import { HttpLoadView } from './HttpLoadView'
import { Badge, Button, InvariantBadge, Stat, StatusPill, Tabs, cx } from './ui'

type Tab = 'results' | 'timeline' | 'artifacts' | 'environment' | 'config'

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'])

/** Pin control for the experiment's regression baseline; only offered for succeeded runs. */
export interface PinControl {
  pinned: boolean
  busy: boolean
  onToggle: () => void
}

export function RunDetail({ runId, descriptor, pin }: { runId: string; descriptor: ScenarioDescriptor; pin?: PinControl }) {
  const qc = useQueryClient()
  const [tab, setTab] = useState<Tab>('results')
  const view = useQuery({
    queryKey: ['run', runId],
    queryFn: () => api.run(runId),
    refetchInterval: (q) => (q.state.data && TERMINAL.has(q.state.data.run.status) ? false : 1000),
  })
  const cancel = useMutation({
    mutationFn: () => api.cancelRun(runId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['run', runId] }),
  })

  if (view.isLoading) return <p className="p-4 text-muted">Loading run…</p>
  if (view.isError || !view.data) return <p className="p-4 text-bad">Could not load run.</p>
  const { run } = view.data
  const active = !TERMINAL.has(run.status)
  const progress = run.progressTotal ? Math.min(1, (run.progressDone ?? 0) / run.progressTotal) : null

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h3 className="text-[15px] font-semibold">Run #{run.seq}</h3>
        <StatusPill status={run.status} />
        <Badge tone={run.role === 'BASELINE' ? 'info' : run.role === 'CANDIDATE' ? 'accent' : 'neutral'}>{run.role.toLowerCase()}</Badge>
        <span className="num text-[13px] text-dim">{run.variant}</span>
        {run.label && <span className="text-[13px] text-muted">“{run.label}”</span>}
        <div className="flex-1" />
        {pin && run.status === 'SUCCEEDED' && (
          <Button variant={pin.pinned ? 'primary' : 'secondary'} onClick={pin.onToggle} disabled={pin.busy}
            title={pin.pinned ? 'Stop checking other runs against this one' : 'Check every other succeeded run of this experiment against this one'}>
            {pin.pinned ? <><PinOff size={13} /> Unpin baseline</> : <><Pin size={13} /> Pin as baseline</>}
          </Button>
        )}
        {active && (
          <Button variant="danger" onClick={() => cancel.mutate()} disabled={cancel.isPending || run.cancelRequested}>
            <Square size={13} /> {run.cancelRequested ? 'Cancelling…' : 'Cancel'}
          </Button>
        )}
      </div>

      {active && (
        <div aria-live="polite" className="flex flex-col gap-1.5">
          <div className="h-1.5 overflow-hidden rounded bg-panel-2">
            <div className={cx('h-full bg-info transition-all', progress === null && 'w-1/3 animate-pulse')} style={progress !== null ? { width: `${progress * 100}%` } : undefined} />
          </div>
          <span className="text-[12px] text-muted">
            {run.status === 'QUEUED' ? 'Waiting for the runner (one run executes at a time).' : progress !== null ? `${run.progressDone}/${run.progressTotal} trials (${run.progressPhase})` : 'Starting runner JVM…'}
          </span>
        </div>
      )}

      {run.statusReason && !active && (
        <div role="alert" className={cx('rounded-md border px-3 py-2 text-[13px]', run.status === 'CANCELLED' ? 'border-border text-dim' : 'border-bad/30 bg-bad-soft text-bad')}>
          {run.statusReason}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
        <Stat label="Trials" value={`${run.trials} + ${run.warmupTrials}`} sub="measured + warm-up" />
        <Stat label="Seed" value={run.seed} />
        <Stat label="Duration" value={duration(runDuration(run.startedAt, run.finishedAt))} sub="incl. JVM start" />
        <Stat label="Heap limit" value={`${run.heapMb} MB`} />
        <Stat label="Exit code" value={run.exitCode ?? '—'} />
      </div>

      <Tabs<Tab>
        label="Run evidence"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'results', label: 'Results' },
          { id: 'timeline', label: 'Timeline' },
          { id: 'artifacts', label: `Artifacts${view.data.artifacts.length ? ` (${view.data.artifacts.length})` : ''}` },
          { id: 'environment', label: 'Environment' },
          { id: 'config', label: 'Configuration' },
        ]}
      />
      {tab === 'results' && <Results view={view.data} descriptor={descriptor} />}
      {tab === 'timeline' && <Timeline runId={runId} active={active} />}
      {tab === 'artifacts' && <Artifacts view={view.data} />}
      {tab === 'environment' && <EnvironmentView view={view.data} />}
      {tab === 'config' && (
        <pre className="num overflow-x-auto rounded-md border border-border bg-code p-3 text-[12px]">
          {JSON.stringify({ scenario: run.scenarioId, scenarioVersion: run.scenarioVersion, variant: run.variant, params: run.params, seed: run.seed, trials: run.trials, warmupTrials: run.warmupTrials, heapMb: run.heapMb, timeoutSeconds: run.timeoutSeconds, forgeVersion: run.forgeVersion }, null, 2)}
        </pre>
      )}
    </div>
  )
}

function Results({ view, descriptor }: { view: RunView; descriptor: ScenarioDescriptor }) {
  const result = view.run.result
  const metricNames = Object.keys(view.summaries)
  const primary = metricNames.includes(descriptor.primaryMetric) ? descriptor.primaryMetric : metricNames[0]
  const [metric, setMetric] = useState<string | undefined>(primary)
  if (!result) return <p className="text-muted">No results recorded{view.run.status === 'SUCCEEDED' ? '' : ' for this run'}.</p>
  const chosen = metric && view.summaries[metric] ? metric : primary
  const spec: MetricSpec | undefined = descriptor.metrics.find((m) => m.name === chosen)
  const explorer = result.details.explorer
  const jmh = result.details.jmh
  const httpLoad = result.details.httpLoad

  return (
    <div className="flex flex-col gap-5">
      {result.invariant.held !== null && (
        <div className="flex flex-wrap items-center gap-2 text-[13px]">
          <InvariantBadge held={result.invariant.held} />
          <span className="text-dim">{result.invariant.description}</span>
          <span className="num text-muted">— {result.invariant.violations} violation(s) in {result.invariant.checked} check(s)</span>
        </div>
      )}

      <div>
        <h4 className="mb-1.5 text-[12px] font-medium uppercase tracking-wider text-muted">Recorded observations</h4>
        <ul className="flex flex-col gap-1 text-[13px] text-dim">
          {result.observations.map((o, i) => <li key={i} className="flex gap-2"><span className="text-muted">•</span>{o}</li>)}
        </ul>
        <p className="mt-1.5 text-[11px] text-muted">Generated mechanically from this run's data. They state what was measured, not why.</p>
      </div>

      {explorer && <ExplorerView details={explorer} />}

      {httpLoad && <HttpLoadView details={httpLoad} />}

      {!explorer && chosen && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2">
            <label htmlFor="metric" className="text-[12px] text-muted">Metric</label>
            <select id="metric" value={chosen} onChange={(e) => setMetric(e.target.value)} className="h-7 rounded-md border border-border bg-bg-elev px-2 text-[12.5px]">
              {metricNames.map((m) => <option key={m} value={m}>{descriptor.metrics.find((x) => x.name === m)?.label ?? m}{m === descriptor.primaryMetric ? ' (primary)' : ''}</option>)}
            </select>
            {spec && <span className="text-[12px] text-muted">{spec.description}</span>}
          </div>
          <TrialChart trials={result.trials} metric={chosen} unit={spec?.unit} median={toNumber(view.summaries[chosen]?.median)} />
        </div>
      )}

      <SummaryTable summaries={view.summaries} descriptor={descriptor} />

      {jmh && (
        <div className="flex flex-col gap-1 text-[13px]">
          <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">JMH</h4>
          <p className="text-dim">
            <span className="num text-text">{fmt(jmh.score)} ± {fmt(jmh.scoreError)} {jmh.scoreUnit}</span> ({jmh.mode}, JMH {jmh.jmhVersion}; ± is JMH's 99.9% interval)
          </p>
          {jmh.forks.map((f) => (
            <p key={f.fork} className="num text-[12px] text-muted">fork {f.fork}: {f.scores.map((s) => fmt(s, 3)).join(', ')}</p>
          ))}
        </div>
      )}

      <details className="text-[13px]">
        <summary className="cursor-pointer text-dim">All trials ({result.trials.length})</summary>
        <div className="mt-2 max-h-80 overflow-auto rounded-md border border-border">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-panel-2 text-left text-muted">
              <tr>
                <th className="px-2 py-1 font-medium">#</th>
                {metricNames.map((m) => <th key={m} className="px-2 py-1 font-medium">{m}</th>)}
                <th className="px-2 py-1 font-medium">invariant</th>
                <th className="px-2 py-1 font-medium">note</th>
              </tr>
            </thead>
            <tbody>
              {result.trials.map((t) => (
                <tr key={t.index} className={cx('border-t border-border', t.invariantHeld === false && 'bg-bad-soft')}>
                  <td className="num px-2 py-1 text-muted">{t.index + 1}</td>
                  {metricNames.map((m) => <td key={m} className="num px-2 py-1">{fmt(t.metrics[m])}</td>)}
                  <td className="px-2 py-1">{t.invariantHeld === null ? '—' : t.invariantHeld ? 'held' : 'violated'}</td>
                  <td className="px-2 py-1 text-muted">{t.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  )
}

export function SummaryTable({ summaries, descriptor }: { summaries: Record<string, Summary>; descriptor: ScenarioDescriptor }) {
  const rows = Object.entries(summaries)
  if (rows.length === 0) return null
  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="w-full text-[12.5px]">
        <caption className="sr-only">Descriptive statistics per metric</caption>
        <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
          <tr>
            {['Metric', 'n', 'median', 'mean', 'stddev', 'p95', 'min', 'max', 'CV'].map((h) => <th key={h} scope="col" className="px-2.5 py-1.5 font-medium">{h}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map(([name, s]) => {
            const spec = descriptor.metrics.find((m) => m.name === name)
            const cv = toNumber(s.cv)
            return (
              <tr key={name} className="border-t border-border">
                <th scope="row" className="px-2.5 py-1.5 text-left font-normal">
                  <span className={name === descriptor.primaryMetric ? 'text-text font-medium' : 'text-dim'}>{spec?.label ?? name}</span>
                  {spec?.unit && <span className="text-muted"> ({spec.unit})</span>}
                </th>
                <td className="num px-2.5 py-1.5 text-muted">{s.n}</td>
                <td className="num px-2.5 py-1.5">{fmt(s.median)}</td>
                <td className="num px-2.5 py-1.5">{fmt(s.mean)}</td>
                <td className="num px-2.5 py-1.5">{fmt(s.stddev)}</td>
                <td className="num px-2.5 py-1.5">{fmt(s.p95)}</td>
                <td className="num px-2.5 py-1.5">{fmt(s.min)}</td>
                <td className="num px-2.5 py-1.5">{fmt(s.max)}</td>
                <td className={cx('num px-2.5 py-1.5', Number.isFinite(cv) && cv > 0.1 ? 'text-warn' : '')} title={Number.isFinite(cv) && cv > 0.1 ? 'High variability: comparisons need more trials' : undefined}>{pct(s.cv)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function Timeline({ runId, active }: { runId: string; active: boolean }) {
  const events = useQuery({ queryKey: ['events', runId], queryFn: () => api.events(runId), refetchInterval: active ? 1000 : false })
  if (!events.data) return <p className="text-muted">Loading events…</p>
  const t0 = events.data.length ? new Date(events.data[0].ts).getTime() : 0
  return (
    <ol className="flex flex-col">
      {events.data.map((e) => (
        <li key={e.id} className="grid grid-cols-[5.5rem_3.5rem_1fr] gap-3 border-l-2 border-border py-1 pl-3 text-[12.5px]">
          <span className="num text-muted">+{((new Date(e.ts).getTime() - t0) / 1000).toFixed(3)}s</span>
          <span className={cx('num text-[11px] font-semibold', e.level === 'ERROR' ? 'text-bad' : e.level === 'WARN' ? 'text-warn' : 'text-muted')}>{e.level}</span>
          <span className="text-dim break-words">{e.message}</span>
        </li>
      ))}
    </ol>
  )
}

function Artifacts({ view }: { view: RunView }) {
  const [open, setOpen] = useState<string | null>(null)
  const content = useQuery({ queryKey: ['artifact', view.run.id, open], queryFn: () => api.artifact(view.run.id, open!), enabled: open !== null })
  if (view.artifacts.length === 0) return <p className="text-muted text-[13px]">This run attached no raw artifacts (thread dumps, tool logs and runner stderr appear here when produced).</p>
  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-wrap gap-2">
        {view.artifacts.map((a) => (
          <li key={a.name}>
            <Button variant={open === a.name ? 'primary' : 'secondary'} onClick={() => setOpen(open === a.name ? null : a.name)}>
              <FileText size={13} /> {a.name} <span className="text-[11px] opacity-70">{bytes(a.sizeBytes)}</span>
            </Button>
          </li>
        ))}
      </ul>
      {open && (
        <pre className="num max-h-[28rem] overflow-auto rounded-md border border-border bg-code p-3 text-[12px] leading-relaxed whitespace-pre-wrap">
          {content.isLoading ? 'Loading…' : content.data}
        </pre>
      )}
    </div>
  )
}

function EnvironmentView({ view }: { view: RunView }) {
  const env = view.run.environment
  if (!env) return <p className="text-muted">No environment captured (the runner did not report).</p>
  const r = (env.runner ?? {}) as Record<string, unknown>
  const h = (env.host ?? {}) as Record<string, unknown>
  const l = (env.launch ?? {}) as Record<string, unknown>
  const rows: [string, unknown][] = [
    ['JVM', `${r.vmName ?? '?'} ${r.javaVersion ?? ''}`],
    ['JIT', r.jitCompiler],
    ['Garbage collectors', Array.isArray(r.garbageCollectors) ? r.garbageCollectors.join(', ') : r.garbageCollectors],
    ['Processors visible to JVM', r.availableProcessors],
    ['Max heap', typeof r.maxHeapBytes === 'number' ? bytes(r.maxHeapBytes) : r.maxHeapBytes],
    ['Runner JVM flags', Array.isArray(l.jvmFlags) ? l.jvmFlags.join(' ') : ''],
    ['CPU', h.cpuModel],
    ['Host logical processors', h.logicalProcessors],
    ['Host memory', typeof h.totalMemoryBytes === 'number' ? bytes(h.totalMemoryBytes) : h.totalMemoryBytes],
    ['OS', `${r.osName ?? h.osName ?? ''} ${r.osVersion ?? ''} (${r.osArch ?? ''})`],
    ['In container', String(h.inContainer)],
    ['Concurrent runs allowed', l.maxConcurrentRuns],
    ['FORGE version', h.forgeVersion],
  ]
  return (
    <div className="flex flex-col gap-3">
      <table className="w-full text-[12.5px]">
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k} className="border-t border-border first:border-t-0">
              <th scope="row" className="w-56 py-1.5 pr-3 text-left font-normal text-muted">{k}</th>
              <td className="num py-1.5 text-dim">{v === undefined || v === null || v === '' ? '—' : String(v)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <details>
        <summary className="cursor-pointer text-[12px] text-muted">Raw environment JSON</summary>
        <pre className="num mt-2 max-h-80 overflow-auto rounded-md border border-border bg-code p-3 text-[11.5px]">{JSON.stringify(env, null, 2)}</pre>
      </details>
    </div>
  )
}
