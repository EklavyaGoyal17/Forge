import { useState } from 'react'
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DatabaseDetails, ExplainCapture, PlanNode } from '../lib/api'
import { fmt } from '../lib/format'
import { CodeBlock, cx } from './ui'

export interface PlanRow {
  depth: number
  node: PlanNode
  title: string
  /** actual rows × loops */
  rows: number
  /** planner's estimate × loops */
  estimate: number
  /** time spent in this node itself (total minus children), ms; parallel and loop effects make it approximate */
  exclusiveMs: number
  /** how far the estimate was off, as a factor ≥ 1 */
  misestimate: number
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0
}

export function nodeTitle(n: PlanNode): string {
  let t = n['Node Type']
  if (n['Index Name']) t += ` using ${n['Index Name']}`
  if (n['Relation Name']) t += ` on ${n['Relation Name']}`
  return t
}

/** Depth-first rows with exclusive time and estimate error, computed from the captured plan only. */
export function planRows(root: PlanNode): PlanRow[] {
  const out: PlanRow[] = []
  const walk = (n: PlanNode, depth: number) => {
    const loops = Math.max(1, num(n['Actual Loops']))
    const total = num(n['Actual Total Time']) * loops
    const children = n.Plans ?? []
    const childTotal = children.reduce((s, c) => s + num(c['Actual Total Time']) * Math.max(1, num(c['Actual Loops'])), 0)
    const rows = num(n['Actual Rows']) * loops
    const estimate = num(n['Plan Rows']) * loops
    const misestimate = Math.max(rows, 1) / Math.max(estimate, 1)
    out.push({
      depth,
      node: n,
      title: nodeTitle(n),
      rows,
      estimate,
      exclusiveMs: Math.max(0, total - childTotal),
      misestimate: misestimate >= 1 ? misestimate : 1 / misestimate,
    })
    children.forEach((c) => walk(c, depth + 1))
  }
  walk(root, 0)
  return out
}

const DETAIL_KEYS = ['Index Cond', 'Filter', 'Recheck Cond', 'Sort Key', 'Sort Method', 'Join Type', 'Hash Cond', 'Merge Cond', 'Workers Launched']

export function PlanTree({ capture }: { capture: ExplainCapture }) {
  const rows = planRows(capture.plan)
  const totalExclusive = rows.reduce((s, r) => s + r.exclusiveMs, 0) || 1
  return (
    <div className="flex flex-col gap-2">
      <p className="text-[12px] text-muted">
        Execution <span className="num text-text">{fmt(capture.executionMs)} ms</span> · planning <span className="num">{fmt(capture.planningMs)} ms</span> ·
        buffers hit <span className="num">{fmt(capture.sharedHitBlocks)}</span>, read <span className="num">{fmt(capture.sharedReadBlocks)}</span> ·
        rows read by scans <span className="num">{fmt(capture.scannedRows)}</span>
        {capture.truncated && <span className="text-warn"> · plan truncated</span>}
      </p>
      <ol aria-label={`Plan: ${capture.label}`} className="flex flex-col rounded-md border border-border">
        {rows.map((r, i) => {
          const share = r.exclusiveMs / totalExclusive
          const details = DETAIL_KEYS.filter((k) => r.node[k] !== undefined && r.node[k] !== null)
          return (
            <li key={i} className="border-t border-border px-3 py-1.5 first:border-t-0" style={{ paddingLeft: 12 + r.depth * 18 }}>
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-[12.5px]">
                <span className="font-medium text-text">{r.depth > 0 && <span className="text-muted">↳ </span>}{r.title}</span>
                <span className="num text-muted">
                  rows {fmt(r.rows)} <span title="planner estimate">(est. {fmt(r.estimate)})</span>
                </span>
                {r.misestimate >= 10 && (
                  <span className="num text-warn" title="The planner's row estimate was off by at least 10×">estimate off ×{fmt(r.misestimate, 3)}</span>
                )}
                <span className="num text-muted">self {fmt(r.exclusiveMs)} ms</span>
                {num(r.node['Rows Removed by Filter']) > 0 && <span className="num text-muted">removed by filter {fmt(num(r.node['Rows Removed by Filter']))}</span>}
                <span className="num text-muted">hit {fmt(num(r.node['Shared Hit Blocks']))} / read {fmt(num(r.node['Shared Read Blocks']))}</span>
              </div>
              <div className="mt-1 h-1 w-full max-w-xs overflow-hidden rounded bg-panel-2" aria-hidden>
                <div className="h-full bg-info" style={{ width: `${Math.min(100, share * 100)}%` }} />
              </div>
              {details.length > 0 && (
                <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-2 text-[11.5px] text-muted">
                  {details.map((k) => (
                    <div key={k} className="contents">
                      <dt>{k}</dt>
                      <dd className="num break-all text-dim">{Array.isArray(r.node[k]) ? (r.node[k] as unknown[]).join(', ') : String(r.node[k])}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </li>
          )
        })}
      </ol>
      <p className="text-[11px] text-muted">
        "self" is a node's own time (its total minus its children's), from EXPLAIN ANALYZE timing; buffers at each node include its children.
        Rows and times are multiplied by loops.
      </p>
    </div>
  )
}

export function DatabaseView({ details }: { details: DatabaseDetails }) {
  const [planIndex, setPlanIndex] = useState(0)
  const plans = details.plans ?? []
  const plan = plans[Math.min(planIndex, plans.length - 1)]
  const pg = details.postgres ?? {}
  return (
    <section aria-label="Database evidence" className="flex flex-col gap-5">
      <div className="flex flex-col gap-1 text-[13px]">
        <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">PostgreSQL and data</h4>
        <p className="text-dim">
          PostgreSQL <span className="num text-text">{pg.server_version ?? '?'}</span> · shared_buffers {pg.shared_buffers ?? '?'} · work_mem {pg.work_mem ?? '?'} ·
          random_page_cost {pg.random_page_cost ?? '?'} · parallel workers per gather {pg.max_parallel_workers_per_gather ?? '?'} · jit {pg.jit ?? '?'}
        </p>
        {details.dataset && (
          <p className="text-dim">
            Seeded <span className="num text-text">{fmt(details.dataset.txnRows)}</span> transactions over <span className="num">{fmt(details.dataset.accounts)}</span> accounts in{' '}
            <span className="num">{fmt(details.dataset.seedMs)} ms</span> (txn {fmt(details.dataset.txnTableMb)} MB)
          </p>
        )}
        {details.index && (
          <p className="text-dim">
            Index <span className="num text-text">{details.index.name}</span>
            {details.index.buildMs !== undefined && <> built in <span className="num">{fmt(details.index.buildMs)} ms</span></>}
            {details.index.sizeMb !== undefined && <>, <span className="num">{fmt(details.index.sizeMb)} MB</span></>}
          </p>
        )}
        {details.index === null && <p className="text-dim">No secondary index (only primary keys).</p>}
        {details.probe && (
          <p className="text-[12px] text-muted">
            Probe account {details.probe.accountId}: {fmt(details.probe.rows)} rows, checksum <span className="num">{details.probe.checksum}</span> — compare across variants.
          </p>
        )}
      </div>

      {[...(details.queries ?? []), ...(details.statements ?? [])].map((q) => (
        <CodeBlock key={q.label} code={q.sql} caption={`SQL · ${q.label} (exactly as executed)`} />
      ))}

      {plan && (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">EXPLAIN (ANALYZE, BUFFERS)</h4>
            {plans.length > 1 && (
              <select aria-label="Captured plan" value={planIndex} onChange={(e) => setPlanIndex(Number(e.target.value))} className="h-7 rounded-md border border-border bg-bg-elev px-2 text-[12.5px]">
                {plans.map((p, i) => <option key={i} value={i}>{p.label}</option>)}
              </select>
            )}
            {plans.length === 1 && <span className="text-[12px] text-muted">{plan.label}</span>}
          </div>
          <PlanTree capture={plan} />
        </div>
      )}

      {details.sweep && details.sweep.length > 0 && <DepthSweep sweep={details.sweep} />}
    </section>
  )
}

function DepthSweep({ sweep }: { sweep: NonNullable<DatabaseDetails['sweep']> }) {
  const tick = { fill: 'var(--muted)', fontSize: 11 }
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">Page depth sweep (this variant)</h4>
      <figure aria-label="Page time and rows read by page depth" className="h-56 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={sweep} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
            <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
            <XAxis dataKey="page" type="number" scale="log" domain={['dataMin', 'dataMax']} ticks={sweep.map((s) => s.page)} tick={tick} stroke="var(--border)" tickFormatter={(v: number) => fmt(v)} />
            <YAxis yAxisId="ms" tick={tick} stroke="var(--border)" width={56} tickFormatter={(v: number) => fmt(v, 3)} label={{ value: 'ms', angle: -90, position: 'insideLeft', fill: 'var(--muted)', fontSize: 11 }} />
            <YAxis yAxisId="rows" orientation="right" tick={tick} stroke="var(--border)" width={64} tickFormatter={(v: number) => fmt(v)} />
            <Tooltip contentStyle={{ background: 'var(--panel)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 12 }} labelFormatter={(l) => `page ${fmt(Number(l))}`} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Line yAxisId="ms" dataKey="medianMs" name="median page time (ms)" stroke="var(--series-1)" isAnimationActive={false} />
            <Line yAxisId="rows" dataKey="rowsScanned" name="rows read by scans" stroke="var(--series-2)" isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </figure>
      <table className={cx('w-full text-[12.5px]')}>
        <caption className="sr-only">Page depth sweep values</caption>
        <thead className="text-left text-[11px] uppercase tracking-wider text-muted">
          <tr>{['Page', 'Median page time (ms)', 'Rows read by scans', 'Server execution (ms)'].map((h) => <th key={h} scope="col" className="py-1 pr-3 font-medium">{h}</th>)}</tr>
        </thead>
        <tbody>
          {sweep.map((s) => (
            <tr key={s.page} className="border-t border-border">
              <th scope="row" className="num py-1 pr-3 text-left font-normal">{fmt(s.page)}</th>
              <td className="num py-1 pr-3">{fmt(s.medianMs)}</td>
              <td className="num py-1 pr-3">{fmt(s.rowsScanned)}</td>
              <td className="num py-1 pr-3">{fmt(s.explainExecutionMs)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-[11px] text-muted">Five fetches per depth after the measured trials; a few points, not a benchmark of its own.</p>
    </div>
  )
}
