import type { Lab } from './api'

/** Navigation model: every page in the sidebar, the command palette and the shortcut help. */
export interface ModuleInfo {
  path: string
  label: string
  shortcut: string
  lab?: Lab
  blurb: string
}

export const MODULES: ModuleInfo[] = [
  { path: '/', label: 'Dashboard', shortcut: 'g d', blurb: 'Queue, recent runs and open investigations.' },
  { path: '/experiments', label: 'Experiments', shortcut: 'g e', blurb: 'Every experiment, searchable.' },
  { path: '/labs/performance', label: 'Performance Lab', shortcut: 'g p', lab: 'PERFORMANCE', blurb: 'Open-model HTTP load against an embedded loopback service: throughput plateaus, queueing and coordinated-omission-correct latency.' },
  { path: '/labs/jvm', label: 'JVM Lab', shortcut: 'g j', lab: 'JVM', blurb: 'JIT warm-up from the first call, allocation and GC, lock contention — profiled with Java Flight Recorder inside the runner JVM.' },
  { path: '/labs/concurrency', label: 'Concurrency Lab', shortcut: 'g c', lab: 'CONCURRENCY', blurb: 'Races, deadlocks and visibility bugs — under stress and under a controlled scheduler.' },
  { path: '/labs/database', label: 'Database Studio', shortcut: 'g b', lab: 'DATABASE', blurb: 'PostgreSQL plans, indexes, pagination strategies and lock contention on seeded data.' },
  { path: '/labs/failure', label: 'Failure Lab', shortcut: 'g f', lab: 'FAILURE', blurb: 'Latency, errors, dropped connections and restarts injected into a dependency inside the run — and what timeouts, retries, budgets and circuit breakers do about it.' },
  { path: '/labs/algorithms', label: 'Algorithm Lab', shortcut: 'g a', lab: 'ALGORITHMS', blurb: 'How data-structure choices behave on real hardware, measured with JMH.' },
  { path: '/challenges', label: 'Challenge Arena', shortcut: 'g h', blurb: 'Eight measurable engineering problems. Complete only when every criterion passes on your recorded runs — never by AI judgement.' },
  { path: '/investigator', label: 'AI Investigator', shortcut: 'g i', blurb: 'Evidence-grounded analysis of your runs: rule-based findings that cite stored evidence, and optional questions to a model whose answers are checked against it.' },
  { path: '/notebook', label: 'Experiment Notebook', shortcut: 'g n', blurb: 'Your archive of investigations, decisions and reports.' },
]

export const LAB_LABEL: Record<Lab, string> = {
  PERFORMANCE: 'Performance',
  JVM: 'JVM',
  CONCURRENCY: 'Concurrency',
  DATABASE: 'Database',
  FAILURE: 'Failure',
  ALGORITHMS: 'Algorithms',
}
