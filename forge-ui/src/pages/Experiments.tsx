import { useMutation, useQuery } from '@tanstack/react-query'
import { FlaskConical, Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Button, ButtonLink, Card, EmptyState, ExperimentStatusBadge, Field, cx, inputClass, textareaClass } from '../components/ui'
import type { ExperimentSummary, Lab, ScenarioDescriptor } from '../lib/api'
import { ApiError, api } from '../lib/api'
import { relativeTime } from '../lib/format'
import { LAB_LABEL } from '../lib/labs'

export function ExperimentsPage() {
  const [q, setQ] = useState('')
  const [lab, setLab] = useState('')
  const list = useQuery({ queryKey: ['experiments', q, lab], queryFn: () => api.experiments(q, lab) })
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-5 p-6">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex-1">
          <h1 className="text-[22px] font-semibold tracking-tight">Experiments</h1>
          <p className="text-dim">Each experiment is one engineering question, one hypothesis, and the runs that test it.</p>
        </div>
        <ButtonLink to="/experiments/new" variant="primary"><Plus size={14} /> New experiment</ButtonLink>
      </div>
      <div className="flex flex-wrap gap-3">
        <input aria-label="Search experiments" className={cx(inputClass, 'max-w-sm')} placeholder="Search title, question, hypothesis, tags…" value={q} onChange={(e) => setQ(e.target.value)} />
        <select aria-label="Filter by lab" className={cx(inputClass, 'max-w-48')} value={lab} onChange={(e) => setLab(e.target.value)}>
          <option value="">All labs</option>
          {(['CONCURRENCY', 'ALGORITHMS'] as Lab[]).map((l) => <option key={l} value={l.toLowerCase()}>{LAB_LABEL[l]}</option>)}
        </select>
      </div>
      <ExperimentTable rows={list.data} loading={list.isLoading} />
    </div>
  )
}

export function ExperimentTable({ rows, loading }: { rows?: ExperimentSummary[]; loading?: boolean }) {
  if (loading) return <p className="text-muted">Loading…</p>
  if (!rows || rows.length === 0) {
    return (
      <Card>
        <EmptyState icon={<FlaskConical size={28} />} title="No experiments yet" action={<ButtonLink to="/experiments/new" variant="primary">Create the first one</ButtonLink>}>
          Pick a scenario, write down what you expect to happen and why, then let the measurements disagree with you.
        </EmptyState>
      </Card>
    )
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-border bg-panel">
      <table className="w-full text-[13px]">
        <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
          <tr>
            {['Experiment', 'Scenario', 'Status', 'Runs', 'Last run', 'Updated'].map((h) => <th key={h} scope="col" className="px-4 py-2 font-medium">{h}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ experiment: e, runCount, succeededRuns, lastRunAt }) => (
            <tr key={e.id} className="border-t border-border hover:bg-panel-2/50">
              <td className="px-4 py-2.5">
                <Link to={`/experiments/${e.id}`} className="font-medium text-text hover:text-accent">{e.title}</Link>
                <p className="line-clamp-1 text-[12px] text-muted">{e.hypothesis}</p>
              </td>
              <td className="num px-4 py-2.5 text-[12px] text-dim">{e.scenarioId}</td>
              <td className="px-4 py-2.5"><ExperimentStatusBadge status={e.status} /></td>
              <td className="num px-4 py-2.5 text-dim">{succeededRuns}/{runCount}</td>
              <td className="px-4 py-2.5 text-dim">{relativeTime(lastRunAt)}</td>
              <td className="px-4 py-2.5 text-dim">{relativeTime(e.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Presets for the showcase demonstrations; the hypothesis is the user's to write and edit. */
const DEMOS: Record<string, { title: string; question: string; hypothesis: string; tags: string[] }> = {
  race: {
    title: 'The Invisible Race',
    question: 'Does a plain read-modify-write on a shared balance lose deposits under contention, and what does a correct fix cost in throughput?',
    hypothesis: '',
    tags: ['demo', 'concurrency'],
  },
  mystery: {
    title: 'The Performance Mystery',
    question: 'Throughput stops at well under the offered rate while the CPUs are mostly idle. What is the service waiting for, and which change raises the plateau without simply adding threads?',
    hypothesis: '',
    tags: ['demo', 'performance'],
  },
  pagination: {
    title: 'The Query That Wouldn’t Scale',
    question: 'Why does a deep page of the transaction feed get slower with OFFSET, and does a keyset cursor keep it flat?',
    hypothesis: '',
    tags: ['demo', 'database'],
  },
}

export function NewExperimentPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: api.scenarios })
  const demo = DEMOS[params.get('demo') ?? '']
  const [scenarioId, setScenarioId] = useState(params.get('scenario') ?? '')
  const [title, setTitle] = useState(demo?.title ?? '')
  const [question, setQuestion] = useState(demo?.question ?? '')
  const [hypothesis, setHypothesis] = useState(demo?.hypothesis ?? '')
  const [tags, setTags] = useState((demo?.tags ?? []).join(', '))
  const create = useMutation({
    mutationFn: () => api.createExperiment({ title, question, hypothesis, scenarioId, tags: tags.split(',').map((t) => t.trim()).filter(Boolean) }),
    onSuccess: (e) => navigate(`/experiments/${e.id}`),
  })
  const byLab = useMemo(() => {
    const m = new Map<Lab, ScenarioDescriptor[]>()
    scenarios.data?.forEach((s) => m.set(s.lab, [...(m.get(s.lab) ?? []), s]))
    return [...m.entries()]
  }, [scenarios.data])
  const chosen = scenarios.data?.find((s) => s.id === scenarioId)
  const fieldError = (f: string) => (create.error instanceof ApiError ? create.error.fieldErrors.find((e) => e.field === f)?.message : undefined)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-5 p-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">New experiment</h1>
        <p className="text-dim">Hypothesis → configuration → execution → measurement → analysis → improvement → comparison.</p>
      </div>
      <Card title="1 · Scenario">
        <div className="flex flex-col gap-4">
          {byLab.map(([lab, list]) => (
            <fieldset key={lab}>
              <legend className="mb-2 text-[12px] font-medium text-muted">{LAB_LABEL[lab]}</legend>
              <div className="grid gap-2 md:grid-cols-2">
                {list.map((s) => (
                  <label key={s.id} className={cx('flex cursor-pointer flex-col gap-1 rounded-md border p-3', scenarioId === s.id ? 'border-accent bg-accent-soft' : 'border-border hover:border-border-strong')}>
                    <span className="flex items-center gap-2">
                      <input type="radio" name="scenario" checked={scenarioId === s.id} onChange={() => { setScenarioId(s.id); if (!question) setQuestion(s.question) }} className="accent-[var(--accent)]" />
                      <span className="font-medium">{s.title}</span>
                    </span>
                    <span className="pl-5 text-[12.5px] leading-snug text-dim">{s.summary}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))}
        </div>
      </Card>
      <Card title="2 · Question & hypothesis">
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault()
            create.mutate()
          }}
        >
          <Field label="Title" htmlFor="title" error={fieldError('title')}>
            <input id="title" className={inputClass} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} required />
          </Field>
          <Field label="Engineering question" htmlFor="question" hint={chosen ? `Suggested by the scenario: “${chosen.question}”` : undefined}>
            <textarea id="question" rows={2} className={textareaClass} value={question} onChange={(e) => setQuestion(e.target.value)} />
          </Field>
          <Field
            label="Hypothesis (required, written before any run)"
            htmlFor="hypothesis"
            error={fieldError('hypothesis')}
            hint="Predict a direction and a rough size, and say why. The review board flags hypotheses edited after the first run."
          >
            <textarea id="hypothesis" rows={3} className={textareaClass} value={hypothesis} onChange={(e) => setHypothesis(e.target.value)} placeholder="e.g. “The CAS loop keeps every deposit and costs < 2× throughput vs the racy version at 4 threads, because…”" required />
          </Field>
          <Field label="Tags" htmlFor="tags" hint="Comma-separated.">
            <input id="tags" className={inputClass} value={tags} onChange={(e) => setTags(e.target.value)} />
          </Field>
          {create.isError && !(create.error instanceof ApiError && create.error.fieldErrors.length) && <p role="alert" className="text-[13px] text-bad">{(create.error as Error).message}</p>}
          <div>
            <Button type="submit" variant="primary" disabled={!scenarioId || !title.trim() || !hypothesis.trim() || create.isPending}>Create experiment</Button>
          </div>
        </form>
      </Card>
    </div>
  )
}
