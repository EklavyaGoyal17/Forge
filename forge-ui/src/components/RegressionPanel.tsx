import type { RegressionCheck, RegressionGate, RegressionReport } from '../lib/api'
import { fmt } from '../lib/format'
import { Badge } from './ui'

export const GATE_LABEL: Record<RegressionGate, string> = {
  REGRESSION: 'regression',
  IMPROVEMENT: 'improvement',
  NO_DIFFERENCE_DETECTED: 'no difference detected',
  IDENTICAL: 'identical',
  NOT_DIRECTIONAL: 'not directional',
  NOT_COMPARABLE: 'not comparable',
}

const GATE_TONE: Record<RegressionGate, 'bad' | 'ok' | 'neutral' | 'warn'> = {
  REGRESSION: 'bad',
  IMPROVEMENT: 'ok',
  NO_DIFFERENCE_DETECTED: 'neutral',
  IDENTICAL: 'neutral',
  NOT_DIRECTIONAL: 'neutral',
  NOT_COMPARABLE: 'warn',
}

export function GateBadge({ check }: { check: RegressionCheck }) {
  return (
    <Badge tone={GATE_TONE[check.gate]} title={check.reasons.join(' ') || undefined}>
      {GATE_LABEL[check.gate]}
    </Badge>
  )
}

/** Every other succeeded run against the pinned baseline. Renders nothing until a baseline is pinned. */
export function RegressionPanel({ report }: { report: RegressionReport }) {
  if (!report.baselineRunId) return null
  const regressions = report.checks.filter((c) => c.gate === 'REGRESSION').length
  return (
    <section aria-label="Regression gate" className="flex flex-col gap-2 px-4 py-3 text-[13px]">
      <h3 className="text-[12px] font-medium uppercase tracking-wider text-muted">
        Regression gate · pinned baseline #{report.baselineSeq} ({report.baselineVariant})
      </h3>
      {report.checks.length === 0 ? (
        <p className="text-muted">No other succeeded run to check yet.</p>
      ) : (
        <>
          <p className={regressions > 0 ? 'text-bad' : 'text-dim'}>
            {regressions === 0 ? 'No regressions' : `${regressions} regression${regressions === 1 ? '' : 's'}`} among {report.checks.length} checked run{report.checks.length === 1 ? '' : 's'}.
          </p>
          <div className="overflow-x-auto">
            <table className="w-full text-[12.5px]">
              <caption className="sr-only">Runs checked against the pinned baseline</caption>
              <thead className="text-left text-[11px] uppercase tracking-wider text-muted">
                <tr>
                  {['Run', 'Gate', 'Primary ratio', '95% CI', 'Why'].map((h) => <th key={h} scope="col" className="py-1 pr-3 font-medium">{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {report.checks.map((c) => (
                  <tr key={c.runId} className="border-t border-border align-top">
                    <th scope="row" className="num py-1.5 pr-3 text-left font-normal text-muted">#{c.seq} <span className="text-dim">{c.variant}</span></th>
                    <td className="py-1.5 pr-3"><GateBadge check={c} /></td>
                    <td className="num py-1.5 pr-3">{c.primary ? fmt(c.primary.ratioOfMedians) : '—'}</td>
                    <td className="num py-1.5 pr-3">{c.primary ? `[${fmt(c.primary.ciLow)}, ${fmt(c.primary.ciHigh)}]` : '—'}</td>
                    <td className="py-1.5 text-[12px] text-muted">
                      {c.reasons.join(' ')}
                      {c.otherRegressedMetrics.length > 0 && <span className="block text-warn">Also worse (not gating): {c.otherRegressedMetrics.join(', ')}.</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-[11px] text-muted">
            A run is a regression only if it is comparable with the baseline (same parameters, scenario version and environment) and the 95% bootstrap
            interval of the primary metric excludes 1.0 on the worse side — or if it violates an invariant the baseline held.
          </p>
        </>
      )}
    </section>
  )
}
