import { useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router'
import { ButtonLink, Card, EmptyState, Stat, StatusPill } from '../components/ui'
import { api } from '../lib/api'
import { bytes, duration, relativeTime, runDuration } from '../lib/format'
import { ExperimentTable } from './Experiments'

const SHOWCASES: { title: string; text: string; to: string }[] = [
  {
    title: 'The Invisible Race',
    text: 'A shared balance loses deposits under contention. Measure it, enumerate every interleaving, repair it, and verify the invariant — then see why passing tests are not a proof.',
    to: '/experiments/new?scenario=concurrency.lost-update&demo=race',
  },
  {
    title: 'The Query That Wouldn’t Scale',
    text: 'OFFSET vs keyset pagination over seeded transaction history, with EXPLAIN ANALYZE plans side by side.',
    to: '/experiments/new?scenario=database.pagination&demo=pagination',
  },
  {
    title: 'The Performance Mystery',
    text: 'A service whose throughput plateaus under load: open-model load tests, JFR profiles, a fix, and a comparable re-run.',
    to: '/experiments/new?scenario=performance.thread-pool-starvation&demo=mystery',
  },
]

export function Dashboard() {
  const system = useQuery({ queryKey: ['system'], queryFn: api.system, refetchInterval: 5000 })
  const runs = useQuery({ queryKey: ['recent-runs'], queryFn: () => api.recentRuns(8), refetchInterval: 3000 })
  const experiments = useQuery({ queryKey: ['experiments', '', ''], queryFn: () => api.experiments() })
  const h = system.data?.host

  return (
    <div className="mx-auto flex max-w-7xl flex-col gap-6 p-4 sm:p-6">
      <div>
        <h1 className="text-[24px] font-semibold tracking-tight">Don't just write software. Understand how it behaves.</h1>
        <p className="mt-1 max-w-3xl text-dim">
          Every number in FORGE comes from a run you can inspect: its code, parameters, seed, JVM flags, raw trials and environment.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {SHOWCASES.map((s) => (
          <Card key={s.title}>
            <div className="flex h-full flex-col gap-3">
              <h2 className="font-semibold">{s.title}</h2>
              <p className="flex-1 text-[13px] leading-relaxed text-dim">{s.text}</p>
              <ButtonLink to={s.to} variant="primary">Start <ArrowRight size={14} /></ButtonLink>
            </div>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Card title="Recent runs" dense>
          {runs.data && runs.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-[13px]">
                <caption className="sr-only">The most recently queued runs</caption>
                <thead className="text-left text-[11px] uppercase tracking-wider text-muted">
                  <tr>
                    {['Status', 'Scenario / variant', 'Role', 'Duration', 'Queued'].map((h, i) => (
                      <th key={h} scope="col" className={i === 0 ? 'px-4 pt-2 pb-1 font-medium' : i === 4 ? 'px-4 pt-2 pb-1 text-right font-medium' : 'px-2 pt-2 pb-1 font-medium'}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {runs.data.map((r) => (
                    <tr key={r.id} className="border-t border-border">
                      <td className="px-4 py-2"><StatusPill status={r.status} /></td>
                      <td className="px-2 py-2">
                        <Link to={`/experiments/${r.experimentId}`} className="num hover:text-accent">{r.scenarioId}</Link>
                        <span className="num text-muted"> / {r.variant}</span>
                      </td>
                      <td className="px-2 py-2 text-dim">{r.role.toLowerCase()}</td>
                      <td className="num whitespace-nowrap px-2 py-2 text-dim">{duration(runDuration(r.startedAt, r.finishedAt))}</td>
                      <td className="whitespace-nowrap px-4 py-2 text-right text-muted">{relativeTime(r.queuedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState title="Nothing has run yet">Runs appear here as soon as they are queued.</EmptyState>
          )}
        </Card>
        <Card title="Runner">
          {system.data ? (
            <div className="flex flex-col gap-4">
              <div className="grid grid-cols-2 gap-4">
                <Stat label="Queue" value={`${system.data.pendingRuns} pending`} sub={`${system.data.limits.maxConcurrentRuns} at a time · capacity ${system.data.limits.queueCapacity}`} />
                <Stat label="Heap per run" value={`${system.data.limits.defaultHeapMb} MB`} sub={`max ${system.data.limits.maxHeapMb} MB`} />
                <Stat label="CPU" value={String(h?.logicalProcessors ?? '—') + ' threads'} sub={String(h?.cpuModel ?? '')} />
                <Stat label="Memory" value={bytes(h?.totalMemoryBytes as number)} sub={`${h?.osName ?? ''}${h?.inContainer ? ' · container' : ''}`} />
              </div>
              <p className="text-[12px] leading-relaxed text-muted">{system.data.isolation}</p>
            </div>
          ) : system.isError ? (
            <p className="text-bad">The FORGE API is not reachable on this origin.</p>
          ) : (
            <p className="text-muted">Loading…</p>
          )}
        </Card>
      </div>

      <div className="flex flex-col gap-3">
        <h2 className="text-[13px] font-semibold uppercase tracking-wide text-dim">Recent experiments</h2>
        <ExperimentTable rows={experiments.data?.slice(0, 6)} loading={experiments.isLoading} />
      </div>
    </div>
  )
}
