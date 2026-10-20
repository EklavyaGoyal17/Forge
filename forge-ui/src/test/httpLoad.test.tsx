import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { HttpLoadView, ninesX, rateSeries, spectrumRows } from '../components/HttpLoadView'
import type { HttpLoadDetails, HttpLoadTrial, LatencySummary } from '../lib/api'


const summary = (count: number, p50: number, p99: number, max: number): LatencySummary => ({
  count, meanMs: p50, minMs: p50 / 2, p50Ms: p50, p90Ms: p50 * 1.5, p95Ms: p50 * 1.8, p99Ms: p99, p999Ms: max * 0.99, maxMs: max,
})

const trial = (index: number, overrides: Partial<HttpLoadTrial> = {}): HttpLoadTrial => ({
  trial: index,
  scheduled: 1200,
  offered: 900,
  sent: 880,
  ok: 800,
  httpErrors: 0,
  failures: 0,
  timeouts: 100,
  unfinished: 0,
  achievedRps: 187.3,
  serverErrors: 3,
  serverMeasuredRequests: 820,
  occupancyMs: 21.28,
  littleBoundRps: 187.9,
  client: summary(900, 1525.76, 2001.92, 2002.94),
  uncorrected: summary(880, 300.1, 347.39, 360),
  service: summary(820, 21.25, 22.6, 25),
  queueWait: summary(820, 318.7, 529.41, 579.58),
  timerLag: { count: 0 },
  measureStartMs: 1000,
  measureEndMs: 4000,
  series: [
    { tMs: 0, offered: 15, completed: 9, errors: 0, p50Ms: 40, p99Ms: 60, maxMs: 61 },
    { tMs: 50, offered: 15, completed: 10, errors: 1 },
  ],
  ...overrides,
})

const details: HttpLoadDetails = {
  design: 'blocking-pool',
  bindAddress: '127.0.0.1',
  workers: 4,
  config: {
    rate: 300, warmupMs: 1000, durationMs: 3000, poolSize: 4, downstreamMs: 20, cpuWorkUs: 200, cpuIterations: 123456,
    iterationsPerMicro: 617.3, connections: 64, requestTimeoutMs: 2000, windowMs: 50,
  },
  trials: [trial(0), trial(1, { client: summary(900, 1508.35, 1999.5, 2001.92), timeouts: 0, ok: 900 })],
  aggregate: {
    client: summary(1800, 1517, 2001.92, 2002.94),
    uncorrected: summary(1760, 300, 347.39, 360),
    service: summary(1640, 21.25, 22.6, 25),
    queueWait: summary(1640, 318.7, 529.41, 579.58),
    spectrum: {
      client: [
        { percentile: 50, valueMs: 1517, resolvable: true },
        { percentile: 99, valueMs: 2001.92, resolvable: true },
        { percentile: 99.99, valueMs: 2002.94, resolvable: false },
      ],
      uncorrected: [
        { percentile: 50, valueMs: 300, resolvable: true },
        { percentile: 99, valueMs: 347.39, resolvable: true },
      ],
      service: [],
      queueWait: [{ percentile: 50, valueMs: 318.7, resolvable: true }],
    },
  },
  histogram: { unit: 'µs', significantDigits: 3, highestTrackableMs: 60000 },
}

describe('HttpLoadView helpers', () => {
  it('places percentiles on a nines axis', () => {
    expect(ninesX(0)).toBe(1)
    expect(ninesX(50)).toBe(2)
    expect(ninesX(99)).toBeCloseTo(100)
    expect(ninesX(99.9)).toBeCloseTo(1000)
  })

  it('joins the spectrum by percentile and drops unresolvable points', () => {
    const rows = spectrumRows(details)
    expect(rows.map((r) => r.percentile)).toEqual([50, 99])
    expect(rows[0]).toMatchObject({ client: 1517, uncorrected: 300, queueWait: 318.7 })
    expect(rows[1].queueWait).toBeUndefined()
    expect(rows.some((r) => r.percentile === 99.99)).toBe(false)
  })

  it('converts per-window counts into rates', () => {
    expect(rateSeries(details.trials[0].series, 50)[0]).toEqual({ tMs: 0, offered: 300, completed: 180, errors: 0 })
  })
})

describe('HttpLoadView', () => {
  it('renders per-trial client percentiles, a merged-histogram row and the server breakdown', () => {
    render(<HttpLoadView details={details} />)
    const client = screen.getByRole('table', { name: /intended send time/i })
    const rows = within(client).getAllByRole('row')
    expect(rows).toHaveLength(4) // header + 2 trials + merged
    // p99 at/above the 2000 ms request timeout is a lower bound and is marked as such
    expect(within(rows[1]).getByText('≥ 2,001.9')).toHaveAttribute('title', expect.stringMatching(/lower bound/))
    expect(within(rows[1]).getByText('1,525.8')).not.toHaveAttribute('title')
    expect(within(rows[1]).getByText('100 (11.11%)')).toBeInTheDocument()
    expect(within(rows[3]).getByRole('rowheader', { name: 'All trials (merged histograms)' })).toBeInTheDocument()
    expect(within(rows[3]).getByText('100 of 1,800')).toBeInTheDocument()

    const breakdown = screen.getByRole('table', { name: /where the time went/i })
    expect(within(breakdown).getAllByText('347.4')).toHaveLength(2)
    expect(within(breakdown).getAllByText('187.9')).toHaveLength(2)

    expect(screen.getByText(/bound to 127\.0\.0\.1/)).toBeInTheDocument()
    expect(screen.getByRole('figure', { name: /client latency over time, trial 1/i })).toBeInTheDocument()
    expect(screen.getByRole('figure', { name: /latency by percentile/i })).toBeInTheDocument()
  })

  it('does not show p99.9 when the trial has fewer than 1000 values', () => {
    render(<HttpLoadView details={details} />)
    const client = screen.getByRole('table', { name: /intended send time/i })
    const firstTrial = within(client).getAllByRole('row')[1]
    expect(within(firstTrial).getByTitle('not resolvable from 900 values')).toHaveTextContent('—')
  })

  it('labels virtual threads as having no pool', () => {
    render(<HttpLoadView details={{ ...details, workers: 0 }} />)
    expect(screen.getByText(/virtual threads \(no pool\)/)).toBeInTheDocument()
  })
})
