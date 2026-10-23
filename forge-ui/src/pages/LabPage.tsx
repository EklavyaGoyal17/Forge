import { useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { Badge, ButtonLink, Card, EmptyState } from '../components/ui'
import type { Lab } from '../lib/api'
import { api } from '../lib/api'
import { MODULES } from '../lib/labs'
import { ExperimentTable } from './Experiments'

const LAB_INTRO: Partial<Record<Lab, { heading: string; points: string[] }>> = {
  CONCURRENCY: {
    heading: 'Two ways to look at the same code',
    points: [
      'Stress mode runs real threads at full speed on real cores. It tells you how often a bug shows up on this machine — and nothing about whether it can.',
      'Explorer mode runs the same implementation on real threads under a controlled scheduler that lets one thread move between instrumented yield points, and enumerates every interleaving. It produces a concrete counterexample schedule.',
      'Neither proves the absence of bugs: stress testing samples schedules; the explorer is exhaustive only at its granularity and assumes sequential consistency (no JMM reorderings).',
    ],
  },
  DATABASE: {
    heading: 'Every run gets its own throwaway data',
    points: [
      'Each run seeds a deterministic synthetic transaction history into its own schema of a separate lab database, measures, and drops the schema. The same seed gives the same rows on the same PostgreSQL version.',
      "Runs connect as a restricted role that cannot reach FORGE's own database, cannot create databases or roles, and is bound by statement and lock timeouts.",
      'Plans are captured with EXPLAIN (ANALYZE, BUFFERS): they show what PostgreSQL chose for this data and these settings — another data size or fresher statistics can change the choice.',
    ],
  },
  PERFORMANCE: {
    heading: 'Load arrives whether or not you are ready',
    points: [
      'Requests are sent at a constant rate (open model), like independent users. A closed loop of N virtual users slows down with the server and hides overload.',
      'Client latency is measured from the moment each request should have been sent, so time spent waiting for a free connection counts. The latency from the actual send is shown next to it, so you can see how much a naive measurement would hide.',
      'The service runs on 127.0.0.1 inside the same runner JVM as the load generator: they share the CPU and the garbage collector, and nothing leaves the machine.',
    ],
  },
  JVM: {
    heading: 'The profiler runs inside the experiment',
    points: [
      'Each run records Java Flight Recorder in its own runner JVM, started by the scenario around the measured trials. The recording is summarised there — hot methods, allocation sites, GC pauses, contended locks, JIT compilations — and only the summary is stored; the raw .jfr file is deleted with the run directory.',
      'JFR samples: CPU stacks every 10 ms, allocations at most 300 per second, lock waits above a threshold. Tables show where time and bytes went, not exact totals; exact counters (per-thread allocated bytes, BLOCKED/WAITING counts) are recorded next to them.',
      'JIT modes (C1 only, C2 only, interpreter only) are real JVM flags on the runner process, taken from an allow-list. Warm-up is measured per method: every trial loads a fresh copy of the kernel class.',
    ],
  },
  FAILURE: {
    heading: 'Faults are injected only into a service that lives inside the run',
    points: [
      'Each trial starts its own inventory service on 127.0.0.1 inside the runner JVM, injects one planned fault (latency, HTTP 500s, dropped connections, a restart) and stops it. Nothing outside the runner process is ever a target.',
      'The caller is driven at a constant rate, so a slow dependency builds a backlog instead of slowing the load down. The timeline shows each window: success, latency, attempts sent, the backlog and the breaker state.',
      'Retry storms are capped: a run is refused if it could send more than 40,000 attempts, because every timed-out attempt closes a TCP connection.',
    ],
  },
  ALGORITHMS: {
    heading: 'Complexity is a growth rate, not a time',
    points: [
      'Microbenchmarks run under JMH in freshly forked JVMs with warm-up iterations and dead-code-elimination protection — never with a hand-written nanoTime loop.',
      'Every result applies to one size, one distribution and one machine. Re-run at the sizes your system actually uses before deciding.',
    ],
  },
}

export function LabPage({ lab }: { lab: Lab }) {
  const module = MODULES.find((m) => m.lab === lab)!
  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: api.scenarios })
  const experiments = useQuery({ queryKey: ['experiments', '', lab], queryFn: () => api.experiments(undefined, lab.toLowerCase()) })
  const list = scenarios.data?.filter((s) => s.lab === lab) ?? []
  const intro = LAB_INTRO[lab]

  return (
    <div className="mx-auto flex max-w-7xl flex-col gap-6 p-4 sm:p-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">{module.label}</h1>
        <p className="text-dim">{module.blurb}</p>
      </div>
      {intro && (
        <Card title={intro.heading}>
          <ul className="flex list-disc flex-col gap-2 pl-4 text-[13px] leading-relaxed text-dim">
            {intro.points.map((p) => <li key={p}>{p}</li>)}
          </ul>
        </Card>
      )}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {list.map((s) => (
          <Card key={s.id}>
            <div className="flex h-full flex-col gap-3">
              <h2 className="font-semibold">{s.title}</h2>
              <p className="text-[13px] leading-relaxed text-dim">{s.summary}</p>
              <p className="text-[12.5px] italic text-muted">“{s.question}”</p>
              <div className="flex flex-wrap gap-1.5">
                {s.variants.map((v) => (
                  <Badge key={v.id} tone={v.expectedCorrect === false ? 'bad' : v.expectedCorrect ? 'ok' : 'neutral'} title={v.description}>{v.label}</Badge>
                ))}
              </div>
              {s.invariant && <p className="text-[12px] text-muted"><span className="text-dim">Invariant:</span> {s.invariant}</p>}
              <div className="mt-auto">
                <ButtonLink to={`/experiments/new?scenario=${encodeURIComponent(s.id)}`} variant="primary">Start experiment <ArrowRight size={14} /></ButtonLink>
              </div>
            </div>
          </Card>
        ))}
        {list.length === 0 && !scenarios.isLoading && <EmptyState title="No scenarios in this lab yet" />}
      </div>
      <div className="flex flex-col gap-3">
        <h2 className="text-[13px] font-semibold uppercase tracking-wide text-dim">Experiments in this lab</h2>
        <ExperimentTable rows={experiments.data} loading={experiments.isLoading} />
      </div>
    </div>
  )
}
