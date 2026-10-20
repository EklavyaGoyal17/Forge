import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { LivenessView } from '../components/LivenessView'
import type { ExplorerStep, LivenessDetails } from '../lib/api'

const step = (index: number, thread: string, kind: string, detail: string, outcome: string | null = null): ExplorerStep => ({
  index, thread, kind, target: detail.split(' ')[1] ?? '', detail, outcome,
})

const base: LivenessDetails = {
  threads: ['A', 'B'],
  states: 41,
  edges: 82,
  executions: 17,
  complete: true,
  maxStates: 20000,
  safetyViolations: 0,
  firstSafetyViolation: null,
  safetyTrace: null,
  deadlocks: 0,
  deadlockTrace: null,
  livelock: null,
  starvation: {},
  error: null,
  elapsedMs: 12.5,
  fairness: 'weak fairness',
  granularity: 'yield points',
}

// the corridor dance: both raise flags, both see the other, both step aside, both raise again
const cycle = [
  step(7, 'A', 'READ', 'read flagB', 'saw 1'),
  step(8, 'B', 'READ', 'read flagA', 'saw 1'),
  step(9, 'A', 'WRITE', 'write flagA = 0'),
  step(10, 'B', 'WRITE', 'write flagB = 0'),
  step(11, 'A', 'WRITE', 'write flagA = 1'),
  step(12, 'B', 'WRITE', 'write flagB = 1'),
]

const verdict = (label: string) => {
  const row = within(screen.getByRole('list', { name: 'Verdicts' })).getByText(label).closest('li')!
  return row.textContent ?? ''
}

describe('LivenessView', () => {
  it('shows a livelock verdict with the repeating cycle, one column per thread', () => {
    render(<LivenessView details={{
      ...base,
      livelock: { prefix: [step(0, 'A', 'WRITE', 'write flagA = 1'), step(1, 'B', 'WRITE', 'write flagB = 1')], cycle, prefixLength: 2, cycleLength: 6 },
      starvation: { A: { prefix: [], cycle, prefixLength: 0, cycleLength: 6 }, B: { prefix: [], cycle, prefixLength: 0, cycleLength: 6 } },
    }} />)
    expect(verdict('Livelock')).toContain('violated')
    expect(verdict('Safety')).toContain('holds')
    expect(verdict('Starvation')).toContain('A, B')
    expect(screen.getByText(/6-step cycle/)).toBeInTheDocument()
    expect(screen.getByText('repeats forever')).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['step', 'A', 'B'])
    // the cycle is numbered by position, not by the step index it had when first discovered
    expect(screen.getAllByRole('row')[1].textContent).toMatch(/^1/)
    fireEvent.click(screen.getByRole('button', { name: /2 steps that lead into the cycle/ }))
    expect(screen.getByText('leads into the cycle')).toBeInTheDocument()
  })

  it('claims "holds" only for a complete graph', () => {
    const { rerender } = render(<LivenessView details={base} />)
    for (const label of ['Safety', 'Deadlock', 'Livelock', 'Starvation']) expect(verdict(label)).toContain('holds')
    rerender(<LivenessView details={{ ...base, complete: false }} />)
    for (const label of ['Safety', 'Deadlock', 'Livelock', 'Starvation']) expect(verdict(label)).toContain('not proven')
    expect(screen.getByText(/budget 20,000 reached/)).toBeInTheDocument()
  })

  it('reports an exploration error instead of a verdict', () => {
    render(<LivenessView details={{ ...base, complete: false, error: 'non-deterministic replay at step 4' }} />)
    expect(screen.getByText(/stopped without a verdict: non-deterministic replay/)).toBeInTheDocument()
  })
})
