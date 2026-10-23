import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Circle, Trophy } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge, Button, Card, EmptyState, textareaClass } from '../components/ui'
import { ApiError, api, type AttemptView, type ChallengeInfo } from '../lib/api'
import { relativeTime } from '../lib/format'
import { LAB_LABEL } from '../lib/labs'

function progress(a: AttemptView | null) {
  if (!a) return <Badge>not started</Badge>
  if (a.complete) return <Badge tone="ok">completed</Badge>
  return <Badge tone="info">{a.passed} of {a.criteria.length} criteria</Badge>
}

/** All challenges with the state of the latest attempt. */
export function ChallengesPage() {
  const list = useQuery({ queryKey: ['challenges'], queryFn: api.challenges })
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-5 p-4 sm:p-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">Challenge Arena</h1>
        <p className="max-w-3xl text-dim">
          Each challenge starts with a measurable problem in a real scenario. Write your hypothesis, reproduce the problem, find the change that
          fixes it and prove it with runs. A challenge is complete only when every criterion passes on recorded evidence — no AI judgement, no
          self-assessment.
        </p>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {(list.data ?? []).map(({ challenge: c, latest, attempts, everCompleted }) => (
          <Link key={c.id} to={`/challenges/${c.id}`} className="block rounded-lg focus-visible:outline-2">
            <Card className="h-full transition-colors hover:border-accent">
              <div className="mb-1 flex items-center gap-2 text-[12px] text-muted">
                <span className="num">#{c.number}</span>
                {c.lab && <Badge>{LAB_LABEL[c.lab]}</Badge>}
                {progress(latest)}
                {everCompleted && <Trophy size={14} className="text-ok" aria-label="completed at least once" />}
                {attempts > 1 && <span>{attempts} attempts</span>}
              </div>
              <h2 className="text-[15px] font-semibold">{c.title}</h2>
              <p className="mt-1 text-[13px] text-dim">{c.problem}</p>
              <p className="mt-2 text-[12.5px] text-muted"><b className="text-text">Goal:</b> {c.goal}</p>
            </Card>
          </Link>
        ))}
      </div>
    </div>
  )
}

function StartForm({ challenge, onStarted }: { challenge: ChallengeInfo; onStarted: () => void }) {
  const [hypothesis, setHypothesis] = useState('')
  const start = useMutation({ mutationFn: () => api.startChallenge(challenge.id, hypothesis.trim()), onSuccess: onStarted })
  return (
    <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); if (hypothesis.trim()) start.mutate() }}>
      <label htmlFor="challenge-hypothesis" className="text-[13px] font-medium">Your hypothesis — before measuring anything</label>
      <p className="text-[12px] text-muted">{challenge.question}</p>
      <textarea id="challenge-hypothesis" rows={3} className={textareaClass} value={hypothesis} onChange={(e) => setHypothesis(e.target.value)}
                placeholder="What do you think causes the problem, and what change will fix it?" />
      <div className="flex items-center gap-3">
        <Button variant="primary" type="submit" disabled={!hypothesis.trim() || start.isPending}>Start challenge</Button>
        {start.error && <span className="text-[12px] text-bad">{start.error instanceof ApiError ? start.error.message : String(start.error)}</span>}
      </div>
      <p className="text-[11.5px] text-muted">Starting creates an experiment for this attempt; every run you make in it counts as evidence.</p>
    </form>
  )
}

function Attempt({ view }: { view: AttemptView }) {
  const qc = useQueryClient()
  const a = view.attempt
  const c = view.challenge
  const refresh = (v: AttemptView) => qc.setQueryData(['attempt', a.id], v)
  const attempt = useQuery({ queryKey: ['attempt', a.id], queryFn: () => api.attempt(a.id), initialData: view, refetchInterval: 5000 })
  const v = attempt.data
  const [answers, setAnswers] = useState<Record<string, string>>(a.answers ?? {})
  useEffect(() => setAnswers(a.answers ?? {}), [a.id, a.answers])
  const hint = useMutation({ mutationFn: () => api.revealHint(a.id), onSuccess: refresh })
  const save = useMutation({ mutationFn: () => api.saveAnswers(a.id, answers), onSuccess: refresh })
  const queue = useMutation({
    mutationFn: () => api.createRun(a.experimentId, { variant: c.start.variant, params: c.start.params, trials: c.start.trials, role: 'BASELINE', label: 'challenge start' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['attempt', a.id] }),
  })

  return (
    <div className="flex flex-col gap-4">
      {v.complete && (
        <div role="status" className="flex items-center gap-2 rounded-md border border-ok bg-ok-soft px-3 py-2 text-[13px]">
          <Trophy size={16} className="text-ok" aria-hidden /> Completed {v.attempt.completedAt ? relativeTime(v.attempt.completedAt) : ''} — every criterion passed on recorded runs
          {v.attempt.hintsRevealed > 0 ? `, with ${v.attempt.hintsRevealed} hint${v.attempt.hintsRevealed > 1 ? 's' : ''}` : ', without hints'}.
        </div>
      )}
      <Card title={`Verification (${v.passed} of ${v.criteria.length})`}
            actions={<Link to={`/experiments/${a.experimentId}`} className="text-[12px] text-accent hover:underline">open the experiment ({v.successfulRuns} successful runs)</Link>}>
        <ol className="flex flex-col gap-3">
          {v.criteria.map((r) => (
            <li key={r.id} className="flex gap-2">
              {r.status === 'PASS' ? <CheckCircle2 size={18} className="mt-0.5 shrink-0 text-ok" aria-label="passed" /> : <Circle size={18} className="mt-0.5 shrink-0 text-muted" aria-label="not yet" />}
              <div>
                <div className="text-[13px]">{r.description}</div>
                <div className="num text-[12px] text-muted">{r.evidence}</div>
              </div>
            </li>
          ))}
        </ol>
        <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-border pt-3 text-[12.5px]">
          <Button onClick={() => queue.mutate()} disabled={queue.isPending}>Queue the starting run</Button>
          <span className="text-muted">
            {c.start.variant} · {Object.entries(c.start.params).map(([k, val]) => `${k}=${String(val)}`).join(', ') || 'default parameters'} · {c.start.trials} trials
          </span>
          {queue.isSuccess && <span className="text-ok">Queued — follow it on the experiment page.</span>}
          {queue.error && <span className="text-bad">{queue.error instanceof ApiError ? queue.error.message : String(queue.error)}</span>}
        </div>
      </Card>

      <Card title={`Hints (${v.attempt.hintsRevealed} of ${c.hintCount} used)`}>
        {v.hints.length === 0 && <p className="text-[12.5px] text-muted">Try first. Hints are revealed one at a time and the number you used is recorded with the attempt.</p>}
        <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-[13px] text-dim">{v.hints.map((h) => <li key={h}>{h}</li>)}</ol>
        {v.attempt.hintsRevealed < c.hintCount && <Button className="mt-3" onClick={() => hint.mutate()} disabled={hint.isPending}>Reveal the next hint</Button>}
      </Card>

      <Card title="Technical review">
        <p className="mb-3 text-[12px] text-muted">Questions a senior reviewer would ask. Your answers are saved with the attempt and never graded.</p>
        <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
          {c.reviewQuestions.map((q, i) => (
            <div key={q} className="flex flex-col gap-1">
              <label htmlFor={`review-${i}`} className="text-[13px]">{q}</label>
              <textarea id={`review-${i}`} rows={2} className={textareaClass} value={answers[String(i)] ?? ''}
                        onChange={(e) => setAnswers({ ...answers, [String(i)]: e.target.value })} />
            </div>
          ))}
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={save.isPending}>Save answers</Button>
            {save.isSuccess && <span className="text-[12px] text-ok">Saved.</span>}
          </div>
        </form>
      </Card>
    </div>
  )
}

/** One challenge: the problem, then either the start form or the latest attempt. */
export function ChallengePage() {
  const { id = '' } = useParams()
  const qc = useQueryClient()
  const data = useQuery({ queryKey: ['challenge', id], queryFn: () => api.challenge(id) })
  const [restart, setRestart] = useState(false)
  if (data.isLoading) return <p className="p-4 sm:p-6 text-muted">Loading…</p>
  if (!data.data) return <div className="p-4 sm:p-6"><Card><EmptyState title="No such challenge"><Link to="/challenges" className="text-accent">All challenges</Link></EmptyState></Card></div>
  const { challenge: c, attempts } = data.data
  const latest = attempts[0]
  const started = () => {
    setRestart(false)
    qc.invalidateQueries({ queryKey: ['challenge', id] })
    qc.invalidateQueries({ queryKey: ['challenges'] })
  }
  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-5 p-4 sm:p-6">
      <div>
        <Link to="/challenges" className="text-[12px] text-accent hover:underline">← Challenge Arena</Link>
        <h1 className="mt-1 text-[22px] font-semibold tracking-tight">#{c.number} {c.title}</h1>
        <div className="mt-1 flex gap-2 text-[12px] text-muted">{c.lab && <Badge>{LAB_LABEL[c.lab]}</Badge>}<span className="num">{c.scenarioId}</span></div>
      </div>
      <Card title="The problem">
        <p className="text-[13.5px] text-dim">{c.problem}</p>
        <p className="mt-2 text-[13px]"><b>Goal:</b> {c.goal}</p>
        <h3 className="mt-3 text-[12px] font-medium uppercase tracking-wider text-muted">Complete when</h3>
        <ol className="mt-1 list-decimal pl-5 text-[13px] text-dim">{c.criteria.map((x) => <li key={x}>{x}</li>)}</ol>
      </Card>
      {!latest || restart ? (
        <Card title={latest ? 'New attempt' : 'Start'}><StartForm challenge={c} onStarted={started} /></Card>
      ) : (
        <>
          <div className="flex items-center gap-3 text-[12.5px] text-muted">
            <span>Attempt started {relativeTime(latest.attempt.startedAt)}{attempts.length > 1 ? ` · ${attempts.length} attempts` : ''}</span>
            <button type="button" className="text-accent hover:underline" onClick={() => setRestart(true)}>start a new attempt</button>
          </div>
          <Attempt key={latest.attempt.id} view={latest} />
        </>
      )}
    </div>
  )
}
