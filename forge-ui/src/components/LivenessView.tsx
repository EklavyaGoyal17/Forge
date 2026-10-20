import { useState, type ReactNode } from 'react'
import type { ExplorerStep, ExplorerTrace, LivenessDetails, LivenessWitness } from '../lib/api'
import { fmt } from '../lib/format'
import { TraceView } from './ExplorerView'
import { Badge, Stat, Tabs } from './ui'

type Verdict = 'ok' | 'bad' | 'unknown'

function trace(steps: ExplorerStep[], threads: string[], outcome: string, executionOutcome: string): ExplorerTrace {
  // steps of a cycle can repeat an edge, so number them by position, not by their original index
  return {
    outcome,
    executionOutcome,
    waitFor: [],
    totalSteps: steps.length,
    truncated: false,
    threads,
    steps: steps.map((s, i) => ({ ...s, index: i })),
  }
}

function VerdictRow({ label, verdict, children }: { label: string; verdict: Verdict; children: ReactNode }) {
  return (
    <li className="grid grid-cols-[8rem_6.5rem_1fr] items-baseline gap-3 border-t border-border py-2 text-[13px] first:border-t-0">
      <span className="font-medium">{label}</span>
      <span>
        {verdict === 'ok' && <Badge tone="ok">holds</Badge>}
        {verdict === 'bad' && <Badge tone="bad">violated</Badge>}
        {verdict === 'unknown' && <Badge tone="warn">not proven</Badge>}
      </span>
      <span className="text-dim">{children}</span>
    </li>
  )
}

function WitnessView({ witness, threads, label }: { witness: LivenessWitness; threads: string[]; label: string }) {
  const [showPrefix, setShowPrefix] = useState(false)
  return (
    <div className="flex flex-col gap-3">
      <p className="text-[12.5px] text-dim">
        Reached after {fmt(witness.prefixLength)} steps from the start;{' '}
        then the {fmt(witness.cycleLength)}-step cycle below returns to the state it started from, so it can repeat forever. {label}
      </p>
      {witness.prefixLength > 0 && (
        <div>
          <button type="button" className="text-[12px] text-accent underline-offset-2 hover:underline" onClick={() => setShowPrefix((v) => !v)} aria-expanded={showPrefix}>
            {showPrefix ? 'Hide' : 'Show'} the {witness.prefixLength} steps that lead into the cycle
          </button>
          {showPrefix && <div className="mt-2"><TraceView trace={trace(witness.prefix, threads, 'leads into the cycle', 'PREFIX')} /></div>}
        </div>
      )}
      <TraceView trace={trace(witness.cycle, threads, 'repeats forever', 'CYCLE')} />
    </div>
  )
}

/** Verdicts and witnesses from an explicit-state liveness exploration. */
export function LivenessView({ details }: { details: LivenessDetails }) {
  const starved = Object.keys(details.starvation)
  const witnesses: { id: string; label: string; node: ReactNode }[] = []
  if (details.livelock) {
    witnesses.push({ id: 'livelock', label: 'Livelock cycle', node: <WitnessView witness={details.livelock} threads={details.threads} label="Every thread keeps running in it; none makes progress." /> })
  }
  for (const t of starved) {
    witnesses.push({ id: `starve-${t}`, label: `${t} starves`, node: <WitnessView witness={details.starvation[t]} threads={details.threads} label={`${t} takes steps in this cycle but never makes progress; the others may.`} /> })
  }
  if (details.safetyTrace && details.firstSafetyViolation) {
    witnesses.push({ id: 'safety', label: 'Safety violation', node: <TraceView trace={trace(details.safetyTrace, details.threads, details.firstSafetyViolation, 'VIOLATION')} /> })
  }
  if (details.deadlockTrace && details.deadlocks > 0) {
    witnesses.push({ id: 'deadlock', label: 'Deadlock', node: <TraceView trace={trace(details.deadlockTrace, details.threads, 'deadlock: no thread can move', 'DEADLOCK')} /> })
  }
  const [which, setWhich] = useState(witnesses[0]?.id ?? '')
  const current = witnesses.find((w) => w.id === which) ?? witnesses[0]
  const absent: Verdict = details.complete ? 'ok' : 'unknown'

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Reachable states" value={fmt(details.states)} sub={details.complete ? 'complete graph' : `budget ${fmt(details.maxStates)} reached`} />
        <Stat label="Transitions" value={fmt(details.edges)} sub={`${fmt(details.executions)} executions`} />
        <Stat label="Threads" value={details.threads.length} sub={details.threads.join(', ')} />
        <Stat label="Exploration time" value={`${fmt(details.elapsedMs)} ms`} />
      </div>

      {details.error && <p className="rounded-md border border-bad bg-bad-soft px-3 py-2 text-[12.5px]">Exploration stopped without a verdict: {details.error}</p>}

      <ul aria-label="Verdicts" className="rounded-md border border-border px-3">
        <VerdictRow label="Safety" verdict={details.safetyViolations > 0 ? 'bad' : absent}>
          {details.safetyViolations > 0
            ? `${fmt(details.safetyViolations)} reachable state(s): ${details.firstSafetyViolation}`
            : details.complete ? 'No reachable state violates the invariant.' : 'No violation among the explored states.'}
        </VerdictRow>
        <VerdictRow label="Deadlock" verdict={details.deadlocks > 0 ? 'bad' : absent}>
          {details.deadlocks > 0 ? `${fmt(details.deadlocks)} reachable state(s) in which no thread can move.` : 'Some thread can always move.'}
        </VerdictRow>
        <VerdictRow label="Livelock" verdict={details.livelock ? 'bad' : absent}>
          {details.livelock ? 'A fair cycle exists in which no thread makes progress.' : 'Every fair cycle contains progress.'}
        </VerdictRow>
        <VerdictRow label="Starvation" verdict={starved.length > 0 ? 'bad' : absent}>
          {starved.length > 0 ? `Possible for ${starved.join(', ')}.` : 'In every fair cycle, every thread makes progress.'}
        </VerdictRow>
      </ul>

      {witnesses.length > 0 && current && (
        <div className="flex flex-col gap-3">
          <Tabs label="Witnesses" value={current.id} onChange={setWhich} tabs={witnesses.map((w) => ({ id: w.id, label: w.label }))} />
          {current.node}
        </div>
      )}

      <p className="text-[12px] leading-relaxed text-muted">
        Fairness: {details.fairness}. Granularity: {details.granularity}. A cycle shown here is a real schedule (each step was executed);
        "holds" is claimed only when the state graph is complete.
      </p>
    </div>
  )
}
