import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChallengePage, ChallengesPage } from '../pages/Challenges'
import { api, type AttemptView, type ChallengeInfo } from '../lib/api'

const challenge: ChallengeInfo = {
  id: 'race', number: 3, title: 'The invisible race', scenarioId: 'concurrency.lost-update', lab: 'CONCURRENCY',
  problem: 'Two threads deposit into a shared balance.', goal: 'Reproduce, fix, confirm.', question: 'Which implementation preserves every deposit?',
  start: { variant: 'unsynchronized', params: { mode: 'explore', exploreThreads: 2, exploreDeposits: 1 }, trials: 1 },
  hintCount: 3, reviewQuestions: ['Why can stress not prove absence?', 'What does the explorer not model?'],
  criteria: ['Reproduce the bug', 'No violating schedule', 'Holds under stress'],
}

const attempt = (over: Partial<AttemptView> = {}): AttemptView => ({
  challenge,
  attempt: { id: 'a1', challengeId: 'race', experimentId: 'e1', hintsRevealed: 1, answers: { '0': 'presence only' }, startedAt: new Date().toISOString(), completedAt: null },
  experimentTitle: 'Challenge 3: The invisible race', successfulRuns: 1,
  criteria: [
    { id: 'reproduce', description: 'Reproduce the bug', status: 'PASS', evidence: 'Run #1 (unsynchronized): 4 of 6 schedules lose an update', runs: [1] },
    { id: 'no-schedule', description: 'No violating schedule', status: 'NOT_YET', evidence: 'No exhaustive explorer run of a fixed variant with 0 violations yet.', runs: [] },
    { id: 'stress', description: 'Holds under stress', status: 'NOT_YET', evidence: 'No stress run yet.', runs: [] },
  ],
  passed: 1, complete: false, hints: ['Look at the counterexample trace.'], ...over,
})

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/challenges" element={<ChallengesPage />} />
          <Route path="/challenges/:id" element={<ChallengePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('Challenge Arena', () => {
  it('lists challenges with the progress of their latest attempt', async () => {
    vi.spyOn(api, 'challenges').mockResolvedValue([
      { challenge, latest: attempt(), attempts: 1, everCompleted: false },
      { challenge: { ...challenge, id: 'index', number: 2, title: 'The query that wouldn’t scale' }, latest: null, attempts: 0, everCompleted: false },
    ])
    renderAt('/challenges')
    expect(await screen.findByText('The invisible race')).toBeInTheDocument()
    expect(screen.getByText('1 of 3 criteria')).toBeInTheDocument()
    expect(screen.getByText('not started')).toBeInTheDocument()
  })

  it('asks for a hypothesis before an attempt can start', async () => {
    vi.spyOn(api, 'challenge').mockResolvedValue({ challenge, attempts: [] })
    const start = vi.spyOn(api, 'startChallenge').mockResolvedValue(attempt())
    renderAt('/challenges/race')
    const button = await screen.findByRole('button', { name: 'Start challenge' })
    expect(button).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/Your hypothesis/), { target: { value: 'read-then-write loses updates' } })
    fireEvent.click(button)
    await waitFor(() => expect(start).toHaveBeenCalledWith('race', 'read-then-write loses updates'))
  })

  it('shows each criterion with its evidence, counts hints and saves review answers', async () => {
    vi.spyOn(api, 'challenge').mockResolvedValue({ challenge, attempts: [attempt()] })
    vi.spyOn(api, 'attempt').mockResolvedValue(attempt())
    const hint = vi.spyOn(api, 'revealHint').mockResolvedValue(attempt({ hints: ['Look at the counterexample trace.', 'Does volatile make it atomic?'] }))
    const save = vi.spyOn(api, 'saveAnswers').mockResolvedValue(attempt())
    const queue = vi.spyOn(api, 'createRun').mockResolvedValue({} as never)
    renderAt('/challenges/race')
    expect(await screen.findByText('Verification (1 of 3)')).toBeInTheDocument()
    expect(screen.getByText(/4 of 6 schedules lose an update/)).toBeInTheDocument()
    expect(screen.getByLabelText('passed')).toBeInTheDocument()
    expect(screen.getAllByLabelText('not yet')).toHaveLength(2)
    expect(screen.getByText('Hints (1 of 3 used)')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reveal the next hint' }))
    await waitFor(() => expect(hint).toHaveBeenCalledWith('a1'))

    fireEvent.click(screen.getByRole('button', { name: 'Queue the starting run' }))
    await waitFor(() => expect(queue).toHaveBeenCalledWith('e1', expect.objectContaining({ variant: 'unsynchronized', role: 'BASELINE', trials: 1 })))

    expect(screen.getByLabelText('Why can stress not prove absence?')).toHaveValue('presence only')
    fireEvent.change(screen.getByLabelText('What does the explorer not model?'), { target: { value: 'JMM reorderings' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save answers' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith('a1', { '0': 'presence only', '1': 'JMM reorderings' }))
  })

  it('announces completion with the number of hints used', async () => {
    const done = attempt({ complete: true, passed: 3, attempt: { ...attempt().attempt, completedAt: new Date().toISOString(), hintsRevealed: 0 } })
    vi.spyOn(api, 'challenge').mockResolvedValue({ challenge, attempts: [done] })
    vi.spyOn(api, 'attempt').mockResolvedValue(done)
    renderAt('/challenges/race')
    expect(await screen.findByRole('status')).toHaveTextContent('every criterion passed on recorded runs, without hints')
  })
})
