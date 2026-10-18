import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RunConfigForm, validate, visibleParameters } from '../components/RunConfigForm'
import type { ScenarioDescriptor } from '../lib/api'

/** The code viewer splits lines into highlighted spans; match on the whole code element. */
const codeContaining = (text: string) => (_: string, el: Element | null) =>
  el?.tagName === 'CODE' && (el.textContent ?? '').includes(text)

const descriptor: ScenarioDescriptor = {
  id: 'concurrency.lost-update',
  version: 1,
  lab: 'CONCURRENCY',
  title: 'The Lost Update Problem',
  summary: '',
  question: '',
  invariant: 'balance == T × K',
  parameters: [
    { name: 'mode', label: 'Mode', type: 'ENUM', defaultValue: 'stress', min: null, max: null, options: ['stress', 'explore'], unit: null, description: '', appliesToModes: [] },
    { name: 'threads', label: 'Threads', type: 'INTEGER', defaultValue: 4, min: 2, max: 64, options: [], unit: 'threads', description: 'Concurrent threads.', appliesToModes: ['stress'] },
    { name: 'exploreThreads', label: 'Explored threads', type: 'INTEGER', defaultValue: 2, min: 2, max: 3, options: [], unit: 'threads', description: '', appliesToModes: ['explore'] },
  ],
  variants: [
    { id: 'unsynchronized', label: 'Plain field', description: 'racy', expectedCorrect: false, sourceRef: 'x#unsynchronized' },
    { id: 'atomic-cas', label: 'AtomicLong CAS loop', description: 'correct', expectedCorrect: true, sourceRef: 'x#atomic-cas' },
  ],
  metrics: [{ name: 'throughputMopsPerSec', label: 'Throughput', unit: 'M/s', direction: 'HIGHER_IS_BETTER', description: '' }],
  primaryMetric: 'throughputMopsPerSec',
  trials: { defaultTrials: 20, maxTrials: 200, defaultWarmup: 3, maxWarmup: 20 },
  defaultTimeoutSeconds: 120,
  explanation: '',
  caveats: [],
}

describe('RunConfigForm helpers', () => {
  it('shows only the parameters of the selected mode', () => {
    expect(visibleParameters(descriptor, { mode: 'stress' }).map((p) => p.name)).toEqual(['threads'])
    expect(visibleParameters(descriptor, { mode: 'explore' }).map((p) => p.name)).toEqual(['exploreThreads'])
  })

  it('mirrors server bounds', () => {
    const ok = validate(descriptor, { mode: 'stress', threads: 4, exploreThreads: 2 }, 20, 3)
    expect(ok).toEqual([])
    const bad = validate(descriptor, { mode: 'stress', threads: 500, exploreThreads: 2.5 }, 0, 99)
    expect(bad.map((e) => e.field)).toEqual(['params.threads', 'params.exploreThreads', 'trials', 'warmupTrials'])
  })
})

describe('RunConfigForm', () => {
  it('shows the source of the selected variant and submits the configuration', async () => {
    const onSubmit = vi.fn()
    render(
      <RunConfigForm
        descriptor={descriptor}
        sources={{ unsynchronized: 'long current = balance;', 'atomic-cas': 'balance.compareAndSet(current, current + 1)' }}
        onSubmit={onSubmit}
      />,
    )
    expect(screen.getByText('broken reference')).toBeInTheDocument()
    expect(screen.getByText(codeContaining('long current = balance;'))).toBeInTheDocument()

    await userEvent.click(screen.getByLabelText(/AtomicLong CAS loop/))
    expect(screen.getByText(codeContaining('compareAndSet'))).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /queue run/i }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      variant: 'atomic-cas',
      params: { mode: 'stress', threads: 4, exploreThreads: 2 },
      trials: 20,
      warmupTrials: 3,
      role: 'BASELINE',
    }))
  })

  it('blocks submission and explains out-of-range values', async () => {
    const onSubmit = vi.fn()
    render(<RunConfigForm descriptor={descriptor} sources={{}} onSubmit={onSubmit} />)
    fireEvent.change(screen.getByLabelText(/Threads/), { target: { value: '1000' } })
    await userEvent.click(screen.getByRole('button', { name: /queue run/i }))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('must be between 2 and 64')
  })

  it('switching to explore mode hides stress parameters and forces a single trial', async () => {
    const onSubmit = vi.fn()
    render(<RunConfigForm descriptor={descriptor} sources={{}} onSubmit={onSubmit} />)
    await userEvent.click(screen.getByRole('radio', { name: 'explore' }))
    expect(screen.queryByLabelText(/^Threads/)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Explored threads/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /queue run/i }))
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ trials: 1, warmupTrials: 0 }))
  })
})
