import { useQuery } from '@tanstack/react-query'
import { BookOpen } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { Card, EmptyState, ExperimentStatusBadge, cx, inputClass } from '../components/ui'
import type { ExperimentSummary } from '../lib/api'
import { api } from '../lib/api'
import { relativeTime } from '../lib/format'

/** The long-term archive: experiments grouped by month, with what was decided. */
export function NotebookPage() {
  const [q, setQ] = useState('')
  const list = useQuery({ queryKey: ['experiments', q, 'notebook'], queryFn: () => api.experiments(q) })
  const groups = useMemo(() => {
    const m = new Map<string, ExperimentSummary[]>()
    for (const e of list.data ?? []) {
      const key = new Date(e.experiment.createdAt).toLocaleDateString('en-US', { year: 'numeric', month: 'long' })
      m.set(key, [...(m.get(key) ?? []), e])
    }
    return [...m.entries()]
  }, [list.data])

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-5 p-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">Experiment Notebook</h1>
        <p className="text-dim">Your archive of questions, hypotheses, decisions and evidence — comparable months apart because every run records its environment.</p>
      </div>
      <input aria-label="Search notebook" className={cx(inputClass, 'max-w-md')} placeholder="Search…" value={q} onChange={(e) => setQ(e.target.value)} />
      {list.data && list.data.length === 0 && (
        <Card><EmptyState icon={<BookOpen size={28} />} title="The notebook is empty">Every experiment you create is recorded here with its hypothesis, decision and report.</EmptyState></Card>
      )}
      {groups.map(([month, items]) => (
        <section key={month} className="flex flex-col gap-3">
          <h2 className="text-[12px] font-semibold uppercase tracking-wider text-muted">{month}</h2>
          <ol className="flex flex-col gap-3 border-l border-border pl-4">
            {items.map(({ experiment: e, runCount, succeededRuns }) => (
              <li key={e.id} className="relative">
                <span className="absolute -left-[21px] top-2 size-2.5 rounded-full border-2 border-bg bg-accent" aria-hidden />
                <Card>
                  <div className="flex flex-col gap-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link to={`/experiments/${e.id}`} className="font-semibold hover:text-accent">{e.title}</Link>
                      <ExperimentStatusBadge status={e.status} />
                      <span className="num text-[12px] text-muted">{e.scenarioId}</span>
                      <span className="text-[12px] text-muted">· {succeededRuns}/{runCount} runs · {relativeTime(e.updatedAt)}</span>
                    </div>
                    <p className="text-[13px] text-dim"><span className="text-muted">Hypothesis:</span> {e.hypothesis}</p>
                    {e.decision ? (
                      <p className="text-[13px]"><span className="text-muted">Decision:</span> {e.decision}</p>
                    ) : (
                      <p className="text-[12px] text-muted">No decision recorded yet.</p>
                    )}
                    <div className="flex gap-3 text-[12px]">
                      <Link to={`/experiments/${e.id}?tab=report`} className="text-accent hover:underline">Report</Link>
                      <Link to={`/experiments/${e.id}?tab=review`} className="text-accent hover:underline">Review board</Link>
                    </div>
                  </div>
                </Card>
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  )
}
