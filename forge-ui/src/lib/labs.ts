import type { LucideIcon } from 'lucide-react'
import { BookOpen, Bot, Boxes, Cpu, Database, FlaskConical, Gauge, GitBranch, Swords, Timer, Zap } from 'lucide-react'
import type { Lab } from './api'

/** Navigation model: every page in the sidebar, the command palette and the shortcut help. */
export interface ModuleInfo {
  path: string
  label: string
  shortcut: string
  lab?: Lab
  icon: LucideIcon
  blurb: string
}

export const MODULES: ModuleInfo[] = [
  { path: '/', label: 'Dashboard', shortcut: 'g d', icon: Gauge, blurb: 'Queue, recent runs and open investigations.' },
  { path: '/experiments', label: 'Experiments', shortcut: 'g e', icon: FlaskConical, blurb: 'Every experiment, searchable.' },
  { path: '/labs/performance', label: 'Performance Lab', shortcut: 'g p', lab: 'PERFORMANCE', icon: Timer, blurb: 'Open-model HTTP load against an embedded loopback service: throughput plateaus, queueing and coordinated-omission-correct latency.' },
  { path: '/labs/jvm', label: 'JVM Lab', shortcut: 'g j', lab: 'JVM', icon: Cpu, blurb: 'JIT warm-up from the first call, allocation and GC, lock contention — profiled with Java Flight Recorder inside the runner JVM.' },
  { path: '/labs/concurrency', label: 'Concurrency Lab', shortcut: 'g c', lab: 'CONCURRENCY', icon: GitBranch, blurb: 'Races, deadlocks and visibility bugs — under stress and under a controlled scheduler.' },
  { path: '/labs/database', label: 'Database Studio', shortcut: 'g b', lab: 'DATABASE', icon: Database, blurb: 'PostgreSQL plans, indexes, pagination strategies and lock contention on seeded data.' },
  { path: '/labs/failure', label: 'Failure Lab', shortcut: 'g f', lab: 'FAILURE', icon: Zap, blurb: 'Latency, errors, dropped connections and restarts injected into a dependency inside the run — and what timeouts, retries, budgets and circuit breakers do about it.' },
  { path: '/labs/algorithms', label: 'Algorithm Lab', shortcut: 'g a', lab: 'ALGORITHMS', icon: Boxes, blurb: 'How data-structure choices behave on real hardware, measured with JMH.' },
  { path: '/challenges', label: 'Challenge Arena', shortcut: 'g h', icon: Swords, blurb: 'Eight measurable engineering problems. Complete only when every criterion passes on your recorded runs — never by AI judgement.' },
  { path: '/investigator', label: 'AI Investigator', shortcut: 'g i', icon: Bot, blurb: 'Evidence-grounded analysis of your runs: rule-based findings that cite stored evidence, and optional questions to a model whose answers are checked against it.' },
  { path: '/notebook', label: 'Experiment Notebook', shortcut: 'g n', icon: BookOpen, blurb: 'Your archive of investigations, decisions and reports.' },
]

export const LAB_LABEL: Record<Lab, string> = {
  PERFORMANCE: 'Performance',
  JVM: 'JVM',
  CONCURRENCY: 'Concurrency',
  DATABASE: 'Database',
  FAILURE: 'Failure',
  ALGORITHMS: 'Algorithms',
}
