import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import axe from 'axe-core'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Layout } from '../components/Layout'
import { FailureView } from '../components/FailureView'
import { InvestigatorPanel } from '../components/InvestigatorPanel'
import { LivenessView } from '../components/LivenessView'
import { RunConfigForm } from '../components/RunConfigForm'
import { api, type FailureDetails, type LivenessDetails, type ScenarioDescriptor } from '../lib/api'
import { ChallengePage } from '../pages/Challenges'

/**
 * Automated accessibility checks (axe-core, WCAG 2.1 A/AA rules) on the main views. jsdom has no
 * layout engine, so colour contrast and anything that needs rendered geometry are not checked here;
 * those were reviewed in the browser. Passing this is necessary, not sufficient.
 */
async function violations(node: Element) {
  const result = await axe.run(node, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    rules: { 'color-contrast': { enabled: false } },
  })
  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`)
}

function withProviders(ui: ReactNode, path = '/', routePath = '*') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path={routePath} element={ui} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

const descriptor: ScenarioDescriptor = {
  id: 'concurrency.lost-update', version: 1, lab: 'CONCURRENCY', title: 'Lost update', summary: '', question: '', invariant: 'balance == T × K',
  parameters: [
    { name: 'mode', label: 'Mode', type: 'ENUM', defaultValue: 'stress', min: null, max: null, options: ['stress', 'explore'], unit: null, description: 'How to run.', appliesToModes: [] },
    { name: 'threads', label: 'Threads', type: 'INTEGER', defaultValue: 4, min: 2, max: 64, options: [], unit: 'threads', description: 'Concurrent threads.', appliesToModes: ['stress'] },
  ],
  variants: [
    { id: 'unsynchronized', label: 'Plain field', description: 'racy', expectedCorrect: false, sourceRef: 'x#u', modes: [], jvmFlags: [] },
    { id: 'atomic-cas', label: 'CAS loop', description: 'correct', expectedCorrect: true, sourceRef: 'x#a', modes: [], jvmFlags: [] },
  ],
  metrics: [{ name: 'throughputMopsPerSec', label: 'Throughput', unit: 'M/s', direction: 'HIGHER_IS_BETTER', description: '' }],
  primaryMetric: 'throughputMopsPerSec', trials: { defaultTrials: 20, maxTrials: 200, defaultWarmup: 3, maxWarmup: 20 },
  defaultTimeoutSeconds: 120, explanation: '', caveats: [],
}

describe('accessibility (axe-core)', () => {
  it('detects known violations (so an empty result means something)', async () => {
    const { container } = render(<div><img src="x.png" /><input type="text" /></div>)
    const found = await violations(container)
    expect(found.some((v) => v.startsWith('image-alt'))).toBe(true)
    expect(found.some((v) => v.startsWith('label'))).toBe(true)
  })

  it('the application shell: landmarks, navigation and controls', async () => {
    vi.spyOn(api, 'system').mockResolvedValue(undefined as never)
    const { container } = withProviders(<Layout />)
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument()
    expect(await violations(container)).toEqual([])
  })

  it('the run configuration form', async () => {
    const { container } = render(<RunConfigForm descriptor={descriptor} sources={{ unsynchronized: 'balance = current + 1;', 'atomic-cas': 'cas();' }} onSubmit={() => {}} />)
    expect(await violations(container)).toEqual([])
  })

  it('liveness verdicts and a witness trace', async () => {
    const details: LivenessDetails = {
      threads: ['A', 'B'], states: 21, edges: 42, executions: 8, complete: true, maxStates: 20000,
      safetyViolations: 0, firstSafetyViolation: null, safetyTrace: null, deadlocks: 0, deadlockTrace: null,
      livelock: { prefix: [], cycle: [{ index: 0, thread: 'A', kind: 'READ', target: 'flagB', detail: 'read flagB', outcome: 'saw 1' }], prefixLength: 0, cycleLength: 1 },
      starvation: {}, error: null, elapsedMs: 10, fairness: 'weak', granularity: 'yield points',
    }
    const { container } = render(<LivenessView details={details} />)
    expect(await violations(container)).toEqual([])
  })

  it('the failure timeline and request accounting', async () => {
    const details: FailureDetails = {
      policy: { attemptTimeoutMs: 200, maxRetries: 3, backoffBaseMs: 50, retryBudget: -1, breaker: true },
      config: { fault: 'latency', faultStartMs: 100, faultEndMs: 300, measureStartMs: 100, measureEndMs: 400, windowMs: 100, rate: 50,
        downstreamWorkers: 4, downstreamLatencyMs: 10, healthyCapacityRps: 400, requestTimeoutMs: 2000, bindAddress: '127.0.0.1' },
      trials: [{
        trial: 0, series: [{ tMs: 0, offered: 5, completed: 5, errors: 0, attempts: 5, backlog: 0, breaker: 0, successPercent: 100 }],
        offered: 5, ok: 5, httpErrors: 0, failures: 0, timeouts: 0, unfinished: 0, attempts: 5, retries: 0, shortCircuited: 0, budgetDenied: 0,
        receivedMinusSent: 0, downstream: { received: 5, served: 5, errors: 0, dropped: 0, wasted: 0, restarts: 0 }, breakerTransitions: [], recoveryMs: 50,
      }],
    }
    const { container } = render(<FailureView details={details} />)
    expect(await violations(container)).toEqual([])
  })

  it('the investigator panel with findings, evidence and an answer', async () => {
    vi.spyOn(api, 'investigation').mockResolvedValue({
      experimentId: 'e1', provider: { provider: 'none', model: null, enabled: false, sendsEvidenceOffHost: false, description: 'Rule-based.' },
      evidence: [{ id: 'R1.m.x', kind: 'METRIC', title: 'Run #1 x', runSeq: 1, text: 'median 3' }],
      findings: [{ kind: 'OBSERVATION', rule: 'primary-metric', text: 'Run #1: median 3.', citations: ['R1.m.x'] }],
    })
    vi.spyOn(api, 'investigations').mockResolvedValue([{
      id: 'i1', experimentId: 'e1', question: 'Why?', provider: 'rules', model: null, answer: 'Because [R1.m.x].', citations: ['R1.m.x'],
      check: { unknownCitations: [], unverifiedNumbers: [], checkedNumbers: 0 }, evidenceIds: ['R1.m.x'], createdAt: new Date().toISOString(),
    }])
    const { container } = withProviders(<InvestigatorPanel experimentId="e1" />)
    await screen.findByText('Observations')
    expect(await violations(container)).toEqual([])
  })

  it('a challenge attempt with criteria, hints and review questions', async () => {
    const challenge = {
      id: 'race', number: 3, title: 'The invisible race', scenarioId: 'concurrency.lost-update', lab: 'CONCURRENCY' as const, problem: 'p', goal: 'g', question: 'q',
      start: { variant: 'unsynchronized', params: { mode: 'explore' }, trials: 1 }, hintCount: 2, reviewQuestions: ['Why?'], criteria: ['Reproduce'],
    }
    const view = {
      challenge, attempt: { id: 'a1', challengeId: 'race', experimentId: 'e1', hintsRevealed: 1, answers: {}, startedAt: new Date().toISOString(), completedAt: null },
      experimentTitle: 't', successfulRuns: 1, criteria: [{ id: 'r', description: 'Reproduce', status: 'PASS' as const, evidence: 'Run #1', runs: [1] }],
      passed: 1, complete: true, hints: ['Look at the trace.'],
    }
    vi.spyOn(api, 'challenge').mockResolvedValue({ challenge, attempts: [view] })
    vi.spyOn(api, 'attempt').mockResolvedValue(view)
    const { container } = withProviders(<ChallengePage />, '/challenges/race', '/challenges/:id')
    await screen.findByText('Verification (1 of 1)')
    expect(await violations(container)).toEqual([])
  })
})
