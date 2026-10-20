import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FailureView, breakerOpenRanges, failureRates } from '../components/FailureView'
import type { FailureDetails, FailureSeriesPoint, FailureTrial } from '../lib/api'

const point = (tMs: number, breaker = 0, attempts = 5, backlog = 0): FailureSeriesPoint => ({
  tMs, offered: 5, completed: 5, errors: 0, p50Ms: 10, p99Ms: 12, attempts, backlog, breaker, successPercent: 100,
})

const trial = (over: Partial<FailureTrial> = {}): FailureTrial => ({
  trial: 0,
  series: [point(0), point(100, 0, 5, 40), point(200, 2, 20, 120), point(300, 2, 0, 3), point(400, 1, 5, 0), point(500)],
  offered: 20, ok: 15, httpErrors: 3, failures: 0, timeouts: 2, unfinished: 0,
  attempts: 35, retries: 12, shortCircuited: 4, budgetDenied: 0, receivedMinusSent: 2,
  downstream: { received: 37, served: 30, errors: 5, dropped: 0, wasted: 6, restarts: 0 },
  breakerTransitions: [{ tMs: 210, state: 'OPEN' }, { tMs: 410, state: 'HALF_OPEN' }, { tMs: 450, state: 'CLOSED' }],
  recoveryMs: 150,
  ...over,
})

const details = (trials: FailureTrial[], fault = 'latency'): FailureDetails => ({
  policy: { attemptTimeoutMs: 200, maxRetries: 3, backoffBaseMs: 50, retryBudget: -1, breaker: true },
  config: {
    fault, faultStartMs: 100, faultEndMs: 300, measureStartMs: 100, measureEndMs: 500, windowMs: 100, rate: 50,
    downstreamWorkers: 4, downstreamLatencyMs: 10, healthyCapacityRps: 400, requestTimeoutMs: 2000, bindAddress: '127.0.0.1',
  },
  trials,
})

describe('failure timeline helpers', () => {
  it('merges consecutive open windows into ranges', () => {
    expect(breakerOpenRanges(trial().series, 100)).toEqual([[200, 400]])
    expect(breakerOpenRanges([point(0, 2), point(100, 2)], 100)).toEqual([[0, 200]])
    expect(breakerOpenRanges([point(0)], 100)).toEqual([])
  })

  it('converts per-window counts into per-second rates', () => {
    expect(failureRates([point(0, 0, 5)], 100)[0].attemptsPerSec).toBe(50)
    expect(failureRates([point(0, 0, 5)], 250)[0].attemptsPerSec).toBe(20)
  })
})

describe('FailureView', () => {
  it('summarises success, recovery, sustained-phase attempts per request and the breaker', () => {
    render(<FailureView details={details([trial()])} />)
    expect(screen.getByText('75.0%')).toBeInTheDocument()
    expect(screen.getByText('150 ms')).toBeInTheDocument()
    // attempts in windows 100..400 (sustained phase) = 5 + 20 + 0 + 5 = 30, over 20 requests
    expect(screen.getByText('1.50')).toBeInTheDocument()
    expect(screen.getByText('120')).toBeInTheDocument()
    expect(screen.getByText('1× open')).toBeInTheDocument()
    expect(screen.getByText(/bound to 127.0.0.1/)).toBeInTheDocument()
  })

  it('says when a trial did not recover and lets the user switch trials', () => {
    render(<FailureView details={details([trial(), trial({ trial: 1, recoveryMs: null, ok: 2 })])} />)
    fireEvent.click(screen.getByRole('button', { name: /trial 2 · no recovery/ }))
    expect(screen.getByText('none')).toBeInTheDocument()
    expect(screen.getByText('still failing when load ended')).toBeInTheDocument()
  })

  it('treats a run without a fault as a control run and explains received − sent', () => {
    render(<FailureView details={details([trial({ recoveryMs: null })], 'none')} />)
    expect(screen.getByText('control run')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Request accounting'))
    expect(screen.getByText(/the HTTP client resent requests itself/)).toBeInTheDocument()
  })
})
