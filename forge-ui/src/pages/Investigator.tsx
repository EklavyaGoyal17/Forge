import { useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { InvestigatorPanel } from '../components/InvestigatorPanel'
import { Card, EmptyState, cx, inputClass } from '../components/ui'
import { api } from '../lib/api'
import { LAB_LABEL } from '../lib/labs'
import { relativeTime } from '../lib/format'

/** AI Investigator: pick an experiment, read the rule-based findings, ask questions grounded in its evidence. */
export function InvestigatorPage() {
  const [params, setParams] = useSearchParams()
  const [q, setQ] = useState('')
  const status = useQuery({ queryKey: ['investigator-status'], queryFn: api.investigatorStatus })
  const list = useQuery({ queryKey: ['experiments', q, 'investigator'], queryFn: () => api.experiments(q) })
  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: api.scenarios })
  const selected = params.get('experiment')
  const chosen = list.data?.find((e) => e.experiment.id === selected)?.experiment

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-5 p-4 sm:p-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">AI Investigator</h1>
        <p className="max-w-3xl text-dim">
          An investigator, not an oracle: it reasons only from what your runs recorded, cites every claim, keeps observations apart
          from hypotheses, and suggests the experiment that would settle a question. Without a model it still works — the findings
          come from explicit rules.
        </p>
        {status.data && (
          <p className="mt-2 text-[12.5px] text-muted">
            Provider: <b className="text-text">{status.data.enabled ? `${status.data.provider} · ${status.data.model}` : 'none (rule-based)'}</b>. {status.data.description}
          </p>
        )}
      </div>
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <Card title="Experiments">
          <div className="relative mb-2">
            <Search size={14} className="absolute left-2 top-2.5 text-muted" aria-hidden />
            <input aria-label="Search experiments" className={cx(inputClass, 'pl-7')} placeholder="Search…" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <ul className="flex max-h-[70vh] flex-col gap-1 overflow-y-auto">
            {(list.data ?? []).map(({ experiment: e, succeededRuns }) => {
              const lab = scenarios.data?.find((s) => s.id === e.scenarioId)?.lab
              return (
                <li key={e.id}>
                  <button type="button" onClick={() => setParams({ experiment: e.id })} aria-current={e.id === selected}
                          className={cx('w-full rounded-md px-2 py-1.5 text-left text-[12.5px]', e.id === selected ? 'bg-panel-2 text-text' : 'text-dim hover:bg-panel-2')}>
                    <span className="block truncate">{e.title}</span>
                    <span className="text-[11px] text-muted">{lab ? LAB_LABEL[lab] : e.scenarioId} · {succeededRuns} runs · {relativeTime(e.createdAt)}</span>
                  </button>
                </li>
              )
            })}
          </ul>
        </Card>
        {chosen ? (
          <div className="flex flex-col gap-3">
            <div className="flex items-baseline gap-3">
              <h2 className="text-[16px] font-semibold">{chosen.title}</h2>
              <Link to={`/experiments/${chosen.id}`} className="text-[12px] text-accent hover:underline">open experiment</Link>
            </div>
            <InvestigatorPanel experimentId={chosen.id} />
          </div>
        ) : (
          <Card>
            <EmptyState title="Choose an experiment">
              The investigator works on one experiment at a time: its question, hypothesis, every run's configuration, environment, metrics,
              invariants and observations, the comparisons between runs, and the review board.
            </EmptyState>
          </Card>
        )}
      </div>
    </div>
  )
}
