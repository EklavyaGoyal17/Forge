import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InvestigatorPanel, splitCitations } from '../components/InvestigatorPanel'
import { api, type InvestigationAnalysis, type Investigation } from '../lib/api'

const analysis: InvestigationAnalysis = {
  experimentId: 'e1',
  provider: { provider: 'anthropic', model: 'claude-opus-5-5', enabled: true, sendsEvidenceOffHost: true, description: 'Asking sends evidence to Anthropic.' },
  evidence: [
    { id: 'R1.m.latencyP99Ms', kind: 'METRIC', title: 'Run #1 Client p99 latency (primary)', runSeq: 1, text: 'latencyP99Ms [ms] over 5 trials: median 1998' },
    { id: 'S', kind: 'SCENARIO', title: 'Scenario', runSeq: null, text: 'Caveats: …' },
  ],
  findings: [
    { kind: 'OBSERVATION', rule: 'primary-metric', text: 'Run #1: median latencyP99Ms 1998 ms.', citations: ['R1.m.latencyP99Ms'] },
    { kind: 'HYPOTHESIS', rule: 'heavy-tail', text: 'Requests waited in a queue.', citations: ['R1.m.latencyP99Ms'] },
    { kind: 'NEXT_EXPERIMENT', rule: 'rate-sweep', text: 'Sweep the offered rate.', citations: [] },
  ],
}

const answer = (over: Partial<Investigation> = {}): Investigation => ({
  id: 'i1', experimentId: 'e1', question: 'Why is p99 high?', provider: 'anthropic', model: 'claude-opus-5-5',
  answer: 'p99 is 1998 ms [R1.m.latencyP99Ms] and the queue held 4096 requests [R9.x].',
  citations: ['R1.m.latencyP99Ms', 'R9.x'],
  check: { unknownCitations: ['R9.x'], unverifiedNumbers: ['4096'], checkedNumbers: 2 },
  evidenceIds: ['R1.m.latencyP99Ms', 'S'], createdAt: new Date().toISOString(), ...over,
})

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><InvestigatorPanel experimentId="e1" /></QueryClientProvider>)
}

afterEach(() => vi.restoreAllMocks())

describe('splitCitations', () => {
  it('splits text around bracketed evidence ids', () => {
    expect(splitCitations('a [R1.m.x] b [C1-2]')).toEqual(['a ', { id: 'R1.m.x' }, ' b ', { id: 'C1-2' }])
    expect(splitCitations('no citations [1, 2]')).toEqual(['no citations [1, 2]'])
  })
})

describe('InvestigatorPanel', () => {
  it('groups findings by kind, warns before sending evidence off the host, and shows the answer check', async () => {
    vi.spyOn(api, 'investigation').mockResolvedValue(analysis)
    vi.spyOn(api, 'investigations').mockResolvedValue([answer()])
    renderPanel()
    expect(await screen.findByText('Observations')).toBeInTheDocument()
    expect(screen.getByText('Hypotheses')).toBeInTheDocument()
    expect(screen.getByText('Suggested experiments')).toBeInTheDocument()
    expect(screen.getByText(/Sends the evidence listed on the right to anthropic/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ask claude-opus-5-5' })).toBeDisabled()

    const check = await screen.findByRole('list', { name: 'Answer check' })
    expect(check).toHaveTextContent('Cites evidence that does not exist: R9.x')
    expect(check).toHaveTextContent('derived or invented, check them: 4096')
    // an unknown citation is shown struck through and cannot be opened
    expect(screen.getByTitle('R9.x is not in the evidence')).toBeDisabled()
  })

  it('opens the cited evidence when a citation is clicked', async () => {
    vi.spyOn(api, 'investigation').mockResolvedValue(analysis)
    vi.spyOn(api, 'investigations').mockResolvedValue([])
    renderPanel()
    await screen.findByText('Observations')
    fireEvent.click(screen.getAllByTitle('Show evidence R1.m.latencyP99Ms')[0])
    const item = document.getElementById('evidence-R1.m.latencyP99Ms')!
    expect(within(item).getByText(/median 1998/)).toBeInTheDocument()
  })

  it('submits a question and lists the stored answer', async () => {
    vi.spyOn(api, 'investigation').mockResolvedValue({ ...analysis, provider: { ...analysis.provider, provider: 'none', enabled: false, sendsEvidenceOffHost: false, model: null } })
    const history = vi.spyOn(api, 'investigations').mockResolvedValue([])
    const ask = vi.spyOn(api, 'ask').mockResolvedValue(answer({ provider: 'rules', model: null }))
    renderPanel()
    const box = await screen.findByLabelText('Question')
    fireEvent.change(box, { target: { value: 'What should I run next?' } })
    history.mockResolvedValue([answer({ provider: 'rules', model: null, question: 'What should I run next?' })])
    fireEvent.click(screen.getByRole('button', { name: 'Ask (rule-based)' }))
    await waitFor(() => expect(ask).toHaveBeenCalledWith('e1', 'What should I run next?'))
    expect(await screen.findByText('rule-based')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Answer check' })).not.toBeInTheDocument()
  })
})
