import type { Lab } from './api'

/** Navigation model. `phase` documents when a module is planned; `implemented` must be truthful. */
export interface ModuleInfo {
  path: string
  label: string
  shortcut: string
  lab?: Lab
  implemented: boolean
  phase?: number
  blurb: string
}

export const MODULES: ModuleInfo[] = [
  { path: '/', label: 'Dashboard', shortcut: 'g d', implemented: true, blurb: 'Queue, recent runs and open investigations.' },
  { path: '/experiments', label: 'Experiments', shortcut: 'g e', implemented: true, blurb: 'Every experiment, searchable.' },
  { path: '/labs/performance', label: 'Performance Lab', shortcut: 'g p', lab: 'PERFORMANCE', implemented: true, blurb: 'Open-model HTTP load against an embedded loopback service: throughput plateaus, queueing and coordinated-omission-correct latency.' },
  { path: '/labs/jvm', label: 'JVM Lab', shortcut: 'g j', lab: 'JVM', implemented: true, blurb: 'JIT warm-up from the first call, allocation and GC, lock contention — profiled with Java Flight Recorder inside the runner JVM.' },
  { path: '/labs/concurrency', label: 'Concurrency Lab', shortcut: 'g c', lab: 'CONCURRENCY', implemented: true, blurb: 'Races, deadlocks and visibility bugs — under stress and under a controlled scheduler.' },
  { path: '/labs/database', label: 'Database Studio', shortcut: 'g b', lab: 'DATABASE', implemented: true, blurb: 'PostgreSQL plans, indexes, pagination strategies and lock contention on seeded data.' },
  { path: '/labs/failure', label: 'Failure Lab', shortcut: 'g f', lab: 'FAILURE', implemented: true, blurb: 'Latency, errors, dropped connections and restarts injected into a dependency inside the run — and what timeouts, retries, budgets and circuit breakers do about it.' },
  { path: '/labs/algorithms', label: 'Algorithm Lab', shortcut: 'g a', lab: 'ALGORITHMS', implemented: true, blurb: 'How data-structure choices behave on real hardware, measured with JMH.' },
  { path: '/challenges', label: 'Challenge Arena', shortcut: 'g h', implemented: false, phase: 8, blurb: 'Measurable engineering problems verified by tests and recorded evidence.' },
  { path: '/investigator', label: 'AI Investigator', shortcut: 'g i', implemented: false, phase: 7, blurb: 'Evidence-grounded analysis of your runs, with citations, through a pluggable model provider.' },
  { path: '/notebook', label: 'Experiment Notebook', shortcut: 'g n', implemented: true, blurb: 'Your archive of investigations, decisions and reports.' },
]

export const LAB_LABEL: Record<Lab, string> = {
  PERFORMANCE: 'Performance',
  JVM: 'JVM',
  CONCURRENCY: 'Concurrency',
  DATABASE: 'Database',
  FAILURE: 'Failure',
  ALGORITHMS: 'Algorithms',
}
