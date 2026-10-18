import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TraceView, conflictingSteps } from '../components/ExplorerView'
import type { ExplorerStep, ExplorerTrace } from '../lib/api'

const step = (index: number, thread: string, kind: string, outcome: string | null = null): ExplorerStep => ({
  index, thread, kind, target: 'balance', detail: `${kind.toLowerCase()} balance`, outcome,
})

describe('conflictingSteps', () => {
  it('flags a read whose value was overwritten before the write-back (lost update)', () => {
    // T1 reads 0, T2 reads 0, T1 writes 1, T2 writes 1 → T1's update is lost
    const steps = [step(0, 'T1', 'READ', 'saw 0'), step(1, 'T2', 'READ', 'saw 0'), step(2, 'T1', 'WRITE'), step(3, 'T2', 'WRITE')]
    expect([...conflictingSteps(steps)].sort()).toEqual([1, 2, 3])
  })

  it('does not flag serial execution', () => {
    const steps = [step(0, 'T1', 'READ'), step(1, 'T1', 'WRITE'), step(2, 'T2', 'READ'), step(3, 'T2', 'WRITE')]
    expect(conflictingSteps(steps).size).toBe(0)
  })

  it('treats a failed CAS as harmless and a successful one as interference', () => {
    const failed = [step(0, 'T1', 'READ'), step(1, 'T2', 'CAS', 'failed (value changed)'), step(2, 'T1', 'WRITE')]
    expect(conflictingSteps(failed).size).toBe(0)
    const succeeded = [step(0, 'T1', 'READ'), step(1, 'T2', 'CAS', 'succeeded'), step(2, 'T1', 'WRITE')]
    expect(conflictingSteps(succeeded).size).toBe(3)
  })
})

describe('TraceView', () => {
  it('renders one column per thread and the wait-for cycle of a deadlock', () => {
    const trace: ExplorerTrace = {
      outcome: 'deadlock',
      executionOutcome: 'DEADLOCK',
      waitFor: ['T1 waits for account-B held by T2', 'T2 waits for account-A held by T1'],
      totalSteps: 2,
      truncated: false,
      threads: ['T1', 'T2'],
      steps: [
        { index: 0, thread: 'T1', kind: 'ACQUIRE', target: 'account-A', detail: 'lock account-A', outcome: null },
        { index: 1, thread: 'T2', kind: 'ACQUIRE', target: 'account-B', detail: 'lock account-B', outcome: null },
      ],
    }
    render(<TraceView trace={trace} />)
    expect(screen.getByRole('columnheader', { name: 'T1' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'T2' })).toBeInTheDocument()
    expect(screen.getByText('T1 waits for account-B held by T2')).toBeInTheDocument()
    expect(screen.getByText('deadlock')).toBeInTheDocument()
  })
})
