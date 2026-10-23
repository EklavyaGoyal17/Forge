import { FlaskConical } from 'lucide-react'
import { Link } from 'react-router'
import type { ExperimentSummary } from '../lib/api'
import { relativeTime } from '../lib/format'
import { ButtonLink, Card, EmptyState, ExperimentStatusBadge, QueryError } from './ui'

/**
 * Experiments as a table (Experiments page, Dashboard, lab pages). Loading, a failed request, no
 * experiments at all and no match for a filter are four different messages.
 */
export function ExperimentTable({ rows, loading, error, filtered }: { rows?: ExperimentSummary[]; loading?: boolean; error?: unknown; filtered?: boolean }) {
  if (error) return <QueryError error={error} what="experiments" />
  if (loading) return <p className="text-muted">Loading experiments…</p>
  if (!rows || rows.length === 0) {
    if (filtered) return <Card><p className="text-center text-dim">No experiment matches this search or filter.</p></Card>
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
        <caption className="sr-only">Experiments</caption>
        <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
          <tr>
            {['Experiment', 'Scenario', 'Status', 'Runs ok / all', 'Last run', 'Updated'].map((h) => <th key={h} scope="col" className="whitespace-nowrap px-4 py-2 font-medium">{h}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ experiment: e, runCount, succeededRuns, lastRunAt }) => (
            <tr key={e.id} className="border-t border-border hover:bg-panel-2/50">
              <td className="min-w-48 px-4 py-2.5">
                <Link to={`/experiments/${e.id}`} className="font-medium text-text hover:text-accent">{e.title}</Link>
                <p className="line-clamp-1 text-[12px] text-muted">{e.hypothesis}</p>
              </td>
              <td className="num px-4 py-2.5 text-[12px] text-dim">{e.scenarioId}</td>
              <td className="px-4 py-2.5"><ExperimentStatusBadge status={e.status} /></td>
              <td className="num px-4 py-2.5 text-dim">{succeededRuns}/{runCount}</td>
              <td className="whitespace-nowrap px-4 py-2.5 text-dim">{relativeTime(lastRunAt)}</td>
              <td className="whitespace-nowrap px-4 py-2.5 text-dim">{relativeTime(e.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
