import { Construction } from 'lucide-react'
import { Card } from '../components/ui'
import type { ModuleInfo } from '../lib/labs'

const PLANS: Record<string, { what: string[]; safety: string }> = {
  '/labs/jvm': {
    what: [
      'Start the runner JVM with a Java Flight Recorder recording and parse it with jdk.jfr.consumer.',
      'Allocation pressure, GC pauses, lock contention, CPU samples and blocked threads for a run.',
      'Warm-up / JIT demonstrations and allocation-heavy vs allocation-light variants.',
    ],
    safety: 'Recordings are written only inside the run’s private work directory and size-capped.',
  },
  '/labs/database': {
    what: [
      'A disposable lab database with a restricted role that cannot see FORGE’s own tables.',
      'Seeded synthetic transaction history (controlled random seeds) at several data volumes.',
      'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) capture and plan-tree diffing; index on/off; OFFSET vs keyset pagination; N+1; isolation levels and lock contention.',
    ],
    safety: 'Destructive operations are confined to the lab database and require explicit confirmation.',
  },
  '/labs/failure': {
    what: [
      'A fault-injecting proxy in front of embedded downstream services: latency, errors, refused connections.',
      'Retry storms, timeouts and circuit breakers, with a recovery timeline.',
    ],
    safety: 'Targets are only services the runner starts itself on loopback. No external host can be addressed.',
  },
  '/challenges': {
    what: [
      'Each challenge = a scenario + a verification predicate over recorded results (e.g. “p95 below X with the invariant held in ≥ 20 trials”).',
      'Hints, technical-review questions, and completion only when measured criteria pass — never by AI judgement.',
    ],
    safety: 'Challenges reuse the same isolated runner as every other experiment.',
  },
  '/investigator': {
    what: [
      'A provider-agnostic model adapter (API or local model) that receives only stored evidence: run configs, trial data, observations, artifacts.',
      'Answers must cite run ids and separate observation from hypothesis; a rule-based analyser works with no provider configured.',
    ],
    safety: 'Evidence is sent to a provider only after you configure one; nothing leaves the machine by default.',
  },
}

export function PlannedModule({ module }: { module: ModuleInfo }) {
  const plan = PLANS[module.path]
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-5 p-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">{module.label}</h1>
        <p className="text-dim">{module.blurb}</p>
      </div>
      <Card>
        <div className="flex flex-col gap-4">
          <div className="flex items-center gap-3">
            <Construction className="text-warn" size={22} />
            <div>
              <p className="font-medium">Not implemented yet — planned for phase {module.phase}.</p>
              <p className="text-[13px] text-dim">FORGE shows no placeholder charts or sample numbers for modules that do not exist. This page describes what will be built.</p>
            </div>
          </div>
          {plan && (
            <>
              <ul className="flex list-disc flex-col gap-2 pl-5 text-[13px] leading-relaxed text-dim">
                {plan.what.map((w) => <li key={w}>{w}</li>)}
              </ul>
              <p className="text-[12.5px] text-muted"><span className="text-dim">Safety boundary:</span> {plan.safety}</p>
            </>
          )}
          <p className="text-[12px] text-muted">Acceptance criteria for this phase are listed in docs/ROADMAP.md.</p>
        </div>
      </Card>
    </div>
  )
}
