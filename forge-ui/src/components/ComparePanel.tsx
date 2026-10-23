import { useQuery } from '@tanstack/react-query'
import { AlertTriangle } from 'lucide-react'
import { useState } from 'react'
import type { Run } from '../lib/api'
import { ApiError, api } from '../lib/api'
import { fmt, signedPct, titleCase } from '../lib/format'
import { RatioInterval, StripPlot } from './charts'
import { Badge, EmptyState, InvariantBadge, VERDICT_TONE, inputClass } from './ui'

function runLabel(r: Run) {
  return `#${r.seq} ${r.role.toLowerCase()} · ${r.variant}${r.label ? ` · ${r.label}` : ''}`
}

export function ComparePanel({ runs }: { runs: Run[] }) {
  const ok = runs.filter((r) => r.status === 'SUCCEEDED')
  const latest = (role: string) => [...ok].reverse().find((r) => r.role === role)
  // user choice wins; otherwise default to the latest baseline/candidate as runs complete
  const [baselineChoice, setBaseline] = useState<string>()
  const [candidateChoice, setCandidate] = useState<string>()
  const baseline = baselineChoice ?? latest('BASELINE')?.id ?? ok[0]?.id
  const candidate = candidateChoice ?? latest('CANDIDATE')?.id ?? ok.find((r) => r.id !== baseline)?.id

  const cmp = useQuery({
    queryKey: ['compare', baseline, candidate],
    queryFn: () => api.compare(baseline!, candidate!),
    enabled: Boolean(baseline && candidate),
    retry: false,
  })
  const bRun = useQuery({ queryKey: ['run', baseline], queryFn: () => api.run(baseline!), enabled: Boolean(baseline) })
  const cRun = useQuery({ queryKey: ['run', candidate], queryFn: () => api.run(candidate!), enabled: Boolean(candidate) })

  if (ok.length < 2) {
    return (
      <EmptyState title="Two successful runs are needed to compare">
        Run a baseline and a candidate with the same parameters. FORGE will compute a bootstrap interval for the ratio of medians, a Mann–Whitney test, and check that the runs are actually comparable.
      </EmptyState>
    )
  }

  const values = (runId: string | undefined, metric: string) =>
    (runId === baseline ? bRun.data : cRun.data)?.run.result?.trials
      .map((t) => t.metrics[metric])
      .filter((v): v is number => typeof v === 'number') ?? []

  const primary = cmp.data?.metrics.find((m) => m.primary) ?? cmp.data?.metrics[0]

  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-[12px] text-dim">
          Baseline
          <select className={inputClass} value={baseline} onChange={(e) => setBaseline(e.target.value)}>
            {ok.map((r) => <option key={r.id} value={r.id}>{runLabel(r)}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-[12px] text-dim">
          Candidate
          <select className={inputClass} value={candidate} onChange={(e) => setCandidate(e.target.value)}>
            {ok.map((r) => <option key={r.id} value={r.id}>{runLabel(r)}</option>)}
          </select>
        </label>
      </div>

      {cmp.isError && <p role="alert" className="text-bad text-[13px]">{cmp.error instanceof ApiError ? cmp.error.message : 'Comparison failed.'}</p>}

      {cmp.data && (
        <>
          {cmp.data.comparabilityWarnings.length > 0 && (
            <div role="note" className="flex flex-col gap-1 rounded-md border border-warn/30 bg-warn-soft px-3 py-2 text-[13px] text-warn">
              <span className="flex items-center gap-1.5 font-medium"><AlertTriangle size={14} /> Comparability</span>
              <ul className="ml-5 list-disc text-dim">
                {cmp.data.comparabilityWarnings.map((w) => <li key={w}>{w}</li>)}
              </ul>
            </div>
          )}

          <div className="flex flex-wrap gap-4 text-[13px]">
            <span className="flex items-center gap-2"><span className="text-muted">Baseline invariant</span><InvariantBadge held={cmp.data.baselineInvariantHeld} /></span>
            <span className="flex items-center gap-2"><span className="text-muted">Candidate invariant</span><InvariantBadge held={cmp.data.candidateInvariantHeld} /></span>
          </div>

          {primary && (
            <div className="flex flex-col gap-2 rounded-md border border-border p-4">
              <div className="flex flex-wrap items-center gap-2">
                <h4 className="font-medium">{primary.label}{primary.unit && <span className="text-muted"> ({primary.unit})</span>}</h4>
                <Badge tone={VERDICT_TONE[primary.result.verdict]}>{titleCase(primary.result.verdict)}</Badge>
              </div>
              <p className="text-[13px] leading-relaxed text-dim">{primary.result.interpretation}</p>
              <StripPlot
                baseline={values(baseline, primary.result.metric)}
                candidate={values(candidate, primary.result.metric)}
                labels={[`baseline · ${cmp.data.baselineVariant}`, `candidate · ${cmp.data.candidateVariant}`]}
                unit={primary.unit}
              />
              {primary.result.warnings.length > 0 && (
                <ul className="ml-5 list-disc text-[12px] text-warn">
                  {primary.result.warnings.map((w) => <li key={w}>{w}</li>)}
                </ul>
              )}
            </div>
          )}

          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-[12.5px]">
              <caption className="sr-only">Comparison of every metric both runs recorded</caption>
              <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
                <tr>
                  {['Metric', 'Baseline median', 'Candidate median', 'Change', 'Ratio & 95% CI (log scale, dashed = no change)', 'MW p', 'Verdict'].map((h) => (
                    <th key={h} scope="col" className="px-2.5 py-1.5 font-medium">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {cmp.data.metrics.map((m) => (
                  <tr key={m.result.metric} className="border-t border-border">
                    <th scope="row" className="px-2.5 py-1.5 text-left font-normal">
                      <span className={m.primary ? 'font-medium text-text' : 'text-dim'}>{m.label}</span>
                      {m.unit && <span className="text-muted"> ({m.unit})</span>}
                    </th>
                    <td className="num px-2.5 py-1.5">{fmt(m.result.baseline.median)}</td>
                    <td className="num px-2.5 py-1.5">{fmt(m.result.candidate.median)}</td>
                    <td className="num px-2.5 py-1.5">{signedPct(m.result.relativeChangePercent)}</td>
                    <td className="px-2.5 py-1.5">
                      <div className="flex items-center gap-2">
                        <RatioInterval result={m.result} />
                        <span className="num text-[11px] text-muted">[{fmt(m.result.ciLow, 3)}, {fmt(m.result.ciHigh, 3)}]</span>
                      </div>
                    </td>
                    <td className="num px-2.5 py-1.5 text-dim">{m.result.mannWhitney ? m.result.mannWhitney.pValue.toFixed(4) : '—'}</td>
                    <td className="px-2.5 py-1.5"><Badge tone={VERDICT_TONE[m.result.verdict]}>{titleCase(m.result.verdict)}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {(cmp.data.parameterDifferences.length > 0 || cmp.data.environmentDifferences.length > 0) && (
            <div className="overflow-x-auto rounded-md border border-border">
              <table className="w-full text-[12.5px]">
                <caption className="px-2.5 py-1.5 text-left text-[11px] uppercase tracking-wider text-muted">What else differs between the runs</caption>
                <tbody>
                  {[...cmp.data.parameterDifferences, ...cmp.data.environmentDifferences].map((d) => (
                    <tr key={d.field} className="border-t border-border">
                      <th scope="row" className="px-2.5 py-1.5 text-left font-normal text-dim">{d.label}</th>
                      <td className="num px-2.5 py-1.5">{d.baseline ?? '—'}</td>
                      <td className="num px-2.5 py-1.5">{d.candidate ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-[12px] text-muted leading-relaxed">
            Interval: seeded percentile bootstrap (10,000 resamples) of the ratio of medians. Mann–Whitney U uses a normal approximation. Trials inside one JVM can be autocorrelated, which makes intervals optimistic; replicate with a second run before drawing conclusions.
          </p>
        </>
      )}
    </div>
  )
}
