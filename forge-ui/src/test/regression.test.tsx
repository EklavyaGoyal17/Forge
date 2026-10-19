import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RegressionPanel } from '../components/RegressionPanel'
import type { ComparisonResult, RegressionReport, Summary } from '../lib/api'

const summary = (median: number): Summary => ({ n: 5, mean: median, stddev: 0.1, min: median, max: median, median, p90: median, p95: median, p99: median, cv: 0.01 })

const result = (ratio: number, low: number, high: number, verdict: ComparisonResult['verdict']): ComparisonResult => ({
  metric: 'latencyP99Ms', direction: 'LOWER_IS_BETTER', baseline: summary(24), candidate: summary(24 * ratio),
  ratioOfMedians: ratio, ciLow: low, ciHigh: high, relativeChangePercent: (ratio - 1) * 100, mannWhitney: null,
  verdict, interpretation: '', warnings: [],
})

const report: RegressionReport = {
  baselineRunId: 'b',
  baselineSeq: 1,
  baselineVariant: 'async',
  primaryMetric: 'latencyP99Ms',
  checks: [
    { runId: 'r2', seq: 2, variant: 'blocking-pool', gate: 'REGRESSION', reasons: [], primary: result(81.8, 80.9, 82.5, 'REGRESSION'), otherRegressedMetrics: ['Server queue wait p99'] },
    { runId: 'r3', seq: 3, variant: 'async', gate: 'NOT_COMPARABLE', reasons: ['Parameters differ: [rate].'], primary: result(1.2, 1.1, 1.3, 'REGRESSION'), otherRegressedMetrics: [] },
    { runId: 'r4', seq: 4, variant: 'virtual-threads', gate: 'NO_DIFFERENCE_DETECTED', reasons: [], primary: result(1.01, 0.98, 1.04, 'NO_DIFFERENCE_DETECTED'), otherRegressedMetrics: [] },
  ],
}

describe('RegressionPanel', () => {
  it('renders nothing while no baseline is pinned', () => {
    const { container } = render(<RegressionPanel report={{ baselineRunId: null, baselineSeq: null, baselineVariant: null, primaryMetric: null, checks: [] }} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('lists every checked run with its gate, interval and reasons', () => {
    render(<RegressionPanel report={report} />)
    expect(screen.getByRole('region', { name: 'Regression gate' })).toHaveTextContent('pinned baseline #1 (async)')
    expect(screen.getByText('1 regression among 3 checked runs.')).toBeInTheDocument()
    const rows = within(screen.getByRole('table')).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(within(rows[1]).getByText('regression')).toBeInTheDocument()
    expect(within(rows[1]).getByText('[80.9, 82.5]')).toBeInTheDocument()
    expect(within(rows[1]).getByText(/Also worse \(not gating\): Server queue wait p99/)).toBeInTheDocument()
    // a run with different parameters gets no verdict, even though its own comparison says "regression"
    expect(within(rows[2]).getByText('not comparable')).toHaveAttribute('title', 'Parameters differ: [rate].')
    expect(within(rows[3]).getByText('no difference detected')).toBeInTheDocument()
  })

  it('says so when only the baseline exists', () => {
    render(<RegressionPanel report={{ ...report, checks: [] }} />)
    expect(screen.getByText('No other succeeded run to check yet.')).toBeInTheDocument()
  })
})
