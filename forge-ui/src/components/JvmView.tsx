import { useMemo, useState, type ReactNode } from 'react'
import { CartesianGrid, Line, LineChart, ReferenceArea, ReferenceLine, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis } from 'recharts'
import type { JfrContention, JfrMarker, JfrSummary, JvmDetails, WarmupCompilation, WarmupTrialSeries } from '../lib/api'
import { bytes, fmt, pct } from '../lib/format'
import { axisTick, tooltipStyle } from './charts'
import { cx } from './ui'


/** Class and method without the package: `dev.forge.X$Y.m:12` → `X$Y.m:12`; `byte[]` stays `byte[]`. */
export function shortFrame(frame: string | null | undefined): string {
  if (!frame) return '—'
  const parts = frame.split('.')
  const i = parts.findIndex((p) => /^[A-Z]/.test(p))
  return i <= 0 ? frame : parts.slice(i).join('.')
}

/** HotSpot tiers: 1–3 are C1 (3 = with full profiling), 4 is C2. */
export function tierLabel(level: number, osr: boolean): string {
  const base = level === 4 ? 'C2' : level === 0 ? 'interpreter' : `C1 tier ${level}`
  return osr ? `${base} OSR` : base
}

/** Rolling median over a centred window (shrinks at the edges). */
export function rollingMedian(values: number[], window: number): number[] {
  const half = Math.floor(window / 2)
  return values.map((_, i) => {
    const s = values.slice(Math.max(0, i - half), Math.min(values.length, i + half + 1)).sort((a, b) => a - b)
    const m = (s.length - 1) / 2
    return (s[Math.floor(m)] + s[Math.ceil(m)]) / 2
  })
}

/** Pairs "<name> start" / "<name> end" markers into windows on the JFR timeline. */
export function markerWindows(markers: JfrMarker[]): { name: string; fromMs: number; toMs: number }[] {
  const starts = new Map<string, number>()
  const out: { name: string; fromMs: number; toMs: number }[] = []
  for (const m of markers) {
    if (m.label.endsWith(' start')) starts.set(m.label.slice(0, -6), m.atMs)
    else if (m.label.endsWith(' end')) {
      const name = m.label.slice(0, -4)
      const from = starts.get(name)
      if (from !== undefined) out.push({ name, fromMs: from, toMs: m.atMs })
    }
  }
  return out
}

export function JvmView({ details }: { details: JvmDetails }) {
  const jfr = details.jfr
  const sections: Record<string, ReactNode> = {
    warmup: details.warmup ? <WarmupSection key="warmup" warmup={details.warmup} /> : null,
    allocation: <AllocationSection key="allocation" jfr={jfr} exactBytes={details.allocation?.exactAllocatedBytes} />,
    gc: <GcSection key="gc" jfr={jfr} />,
    locks: <LocksSection key="locks" jfr={jfr} />,
    cpu: <CpuSection key="cpu" jfr={jfr} />,
  }
  const order = details.kind === 'warmup' ? ['warmup', 'cpu', 'gc', 'allocation', 'locks']
    : details.kind === 'allocation' ? ['allocation', 'gc', 'cpu', 'locks']
      : details.kind === 'profile' ? ['cpu', 'locks', 'gc', 'allocation']
        : ['locks', 'cpu', 'gc', 'allocation']

  return (
    <section aria-label="JVM evidence" className="flex flex-col gap-6">
      <div className="flex flex-col gap-1 text-[13px]">
        <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">JVM</h4>
        <p className="text-dim">
          JIT: <span className="text-text">{details.jitMode.compiler ?? 'none (interpreter only)'}</span>
          {details.jitMode.variantFlags.length > 0 && <> · variant flags <code className="num text-text">{details.jitMode.variantFlags.join(' ')}</code></>}
        </p>
        <RecordingLine jfr={jfr} />
      </div>
      {order.map((k) => sections[k])}
    </section>
  )
}

function RecordingLine({ jfr }: { jfr: JfrSummary }) {
  const r = jfr.recording
  const events = Object.values(r.eventCounts).reduce((a, b) => a + b, 0)
  return (
    <details className="text-[12px] text-muted">
      <summary className="cursor-pointer">
        Java Flight Recorder: {fmt(Math.round(r.durationMs))} ms recorded, {bytes(r.fileBytes)} file ({fmt(events)} events) — summarised inside the runner; the raw file was deleted with the run directory.
        {r.windowStartMs !== null && r.windowEndMs !== null && <> Tables cover the measured window {fmt(Math.round(r.windowStartMs))}–{fmt(Math.round(r.windowEndMs))} ms.</>}
        {r.truncatedTables.length > 0 && <> Row-capped tables: {r.truncatedTables.join(', ')}.</>}
      </summary>
      <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <table className="text-[12px]">
          <caption className="text-left text-muted">Settings</caption>
          <tbody>{Object.entries(r.settings).map(([k, v]) => <tr key={k}><th scope="row" className="pr-3 text-left font-normal">{k}</th><td className="num text-dim">{v}</td></tr>)}</tbody>
        </table>
        <table className="text-[12px]">
          <caption className="text-left text-muted">Events in the file</caption>
          <tbody>{Object.entries(r.eventCounts).map(([k, v]) => <tr key={k}><th scope="row" className="pr-3 text-left font-normal">{k}</th><td className="num text-dim">{fmt(v)}</td></tr>)}</tbody>
        </table>
      </div>
    </details>
  )
}

function SectionTitle({ children, note }: { children: ReactNode; note?: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <h4 className="text-[12px] font-medium uppercase tracking-wider text-muted">{children}</h4>
      {note && <p className="text-[12px] text-muted">{note}</p>}
    </div>
  )
}

function ShareBar({ share, tone = 'var(--series-1)' }: { share: number; tone?: string }) {
  return (
    <span className="flex items-center gap-2">
      <span className="h-1.5 w-20 overflow-hidden rounded bg-panel-2" aria-hidden>
        <span className="block h-full" style={{ width: `${Math.min(100, share * 100)}%`, background: tone }} />
      </span>
      <span className="num">{pct(share)}</span>
    </span>
  )
}

function Frame({ frame }: { frame: string | null | undefined }) {
  return <span className="num break-all" title={frame ?? undefined}>{shortFrame(frame)}</span>
}

const th = 'px-2.5 py-1.5 font-medium'
const td = 'px-2.5 py-1.5'

// ---- warm-up ------------------------------------------------------------------------------------

const TIER_COLOR = (level: number) => (level === 4 ? 'var(--series-3)' : 'var(--series-2)')

function WarmupSection({ warmup }: { warmup: NonNullable<JvmDetails['warmup']> }) {
  const [selected, setSelected] = useState(0)
  const trial = warmup.trials[Math.min(selected, warmup.trials.length - 1)]
  return (
    <div className="flex flex-col gap-2">
      <SectionTitle note={<>Every call of <code>work()</code> on {fmt(warmup.workSize)} elements, timed from the very first one in a freshly loaded copy of the class. Vertical lines: the first invocation that started after JFR saw a compilation finish (dashed = on-stack replacement).</>}>
        Warm-up: time per invocation
      </SectionTitle>
      {warmup.trials.length > 1 && (
        <div className="flex items-center gap-2">
          <label htmlFor="warmup-trial" className="text-[12px] text-muted">Trial</label>
          <select id="warmup-trial" value={selected} onChange={(e) => setSelected(Number(e.target.value))} className="h-7 rounded-md border border-border bg-bg-elev px-2 text-[12.5px]">
            {warmup.trials.map((t, i) => <option key={t.trial} value={i}>{t.trial + 1}</option>)}
          </select>
        </div>
      )}
      {trial && <WarmupChart trial={trial} />}
      {trial && <CompilationTable trial={trial} />}
    </div>
  )
}

function WarmupChart({ trial }: { trial: WarmupTrialSeries }) {
  const data = useMemo(() => {
    const us = trial.invocationNanos.map((n) => n / 1000)
    const med = rollingMedian(us, 25)
    return us.map((v, i) => ({ i, us: v, median: med[i] }))
  }, [trial])
  const placed = trial.compilations.filter((c) => c.atInvocation >= 0)
  return (
    <figure aria-label="Time per invocation" className="h-72 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
          <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
          <XAxis dataKey="i" type="number" domain={[0, data.length - 1]} tick={axisTick} stroke="var(--border)" label={{ value: 'invocation', position: 'insideBottomRight', offset: -2, fill: 'var(--muted)', fontSize: 11 }} />
          <YAxis scale="log" domain={['auto', 'auto']} allowDataOverflow tick={axisTick} stroke="var(--border)" width={64} tickFormatter={(v) => `${fmt(v, 2)} µs`} />
          <Tooltip contentStyle={tooltipStyle} labelFormatter={(l) => `invocation ${l}`} formatter={(v: number, name: string) => [`${fmt(v)} µs`, name === 'us' ? 'this call' : 'rolling median (25)']} />
          {placed.map((c, k) => (
            <ReferenceLine key={k} x={c.atInvocation} stroke={TIER_COLOR(c.level)} strokeDasharray={c.osr ? '4 3' : undefined} />
          ))}
          <Line dataKey="us" stroke="var(--series-1)" strokeOpacity={0.35} dot={false} isAnimationActive={false} />
          <Line dataKey="median" stroke="var(--series-1)" strokeWidth={2} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </figure>
  )
}

function CompilationTable({ trial }: { trial: WarmupTrialSeries }) {
  if (trial.compilations.length === 0) {
    return <p className="text-[12.5px] text-muted">JFR recorded no compilation of the kernel in this trial — every invocation ran in the interpreter.</p>
  }
  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="w-full text-[12.5px]">
        <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">JIT compilations of the kernel in trial {trial.trial + 1} (jdk.Compilation)</caption>
        <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
          <tr>{['Method', 'Compiler', 'Code available from', 'Compile time', 'Code size'].map((h) => <th key={h} scope="col" className={th}>{h}</th>)}</tr>
        </thead>
        <tbody>
          {trial.compilations.map((c: WarmupCompilation, k) => (
            <tr key={k} className="border-t border-border">
              <td className={cx(td, 'num')}>{c.method}()</td>
              <td className={td}><span className="inline-block h-2 w-2 rounded-full align-middle" style={{ background: TIER_COLOR(c.level) }} /> {tierLabel(c.level, c.osr)}{!c.succeeded && <span className="text-bad"> (failed)</span>}</td>
              <td className={cx(td, 'num')}>{c.atInvocation >= 0 ? `invocation ${fmt(c.atInvocation)}` : 'after the last invocation'}</td>
              <td className={cx(td, 'num')}>{fmt(c.compileMs)} ms</td>
              <td className={cx(td, 'num')}>{fmt(c.codeSize)} B</td>
            </tr>
          ))}
        </tbody>
      </table>
      {trial.deoptimizations.length > 0 && (
        <p className="px-2.5 py-1.5 text-[12px] text-warn">
          Deoptimized: {trial.deoptimizations.map((d) => `${shortFrame(d.method)} (${d.reason}, ${d.action})`).join('; ')}
        </p>
      )}
    </div>
  )
}

// ---- CPU ----------------------------------------------------------------------------------------

function CpuSection({ jfr }: { jfr: JfrSummary }) {
  const cpu = jfr.cpu
  if (cpu.samples === 0) {
    return (
      <div className="flex flex-col gap-1">
        <SectionTitle>Hot methods</SectionTitle>
        <p className="text-[12.5px] text-muted">No execution samples in the measured window (it may be shorter than the {fmt(cpu.periodMs)} ms sampling period).</p>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      <SectionTitle note={`${fmt(cpu.samples)} jdk.ExecutionSample stacks, one every ${fmt(cpu.periodMs)} ms per thread running Java code. Shares are of samples, not of exact time.`}>
        Hot methods
      </SectionTitle>
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-[12.5px]">
            <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">Top of stack (self): where the CPU was</caption>
            <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
              <tr>{['Method', 'Samples', 'Share', 'Ran as'].map((h) => <th key={h} scope="col" className={th}>{h}</th>)}</tr>
            </thead>
            <tbody>
              {cpu.topSelf.map((r) => (
                <tr key={r.frame} className="border-t border-border">
                  <td className={td}><Frame frame={r.frame} /></td>
                  <td className={cx(td, 'num')}>{fmt(r.samples)}</td>
                  <td className={td}><ShareBar share={r.share} /></td>
                  <td className={cx(td, 'text-[11.5px] text-muted')}>{Object.entries(r.frameTypes ?? {}).map(([k, v]) => `${k} ${v}`).join(' · ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-[12.5px]">
            <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">Scenario methods anywhere on the stack (inclusive)</caption>
            <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
              <tr>{['Method', 'Samples', 'Share'].map((h) => <th key={h} scope="col" className={th}>{h}</th>)}</tr>
            </thead>
            <tbody>
              {cpu.topTotal.map((r) => (
                <tr key={r.frame} className="border-t border-border">
                  <td className={td}><Frame frame={r.frame} /></td>
                  <td className={cx(td, 'num')}>{fmt(r.samples)}</td>
                  <td className={td}><ShareBar share={r.share} tone="var(--series-4)" /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="flex flex-col gap-1.5">
        <h5 className="text-[12px] text-muted">Hottest stack tops — innermost frame first, callers below</h5>
        <ol className="flex flex-col gap-1.5">
          {cpu.hotPaths.map((p, k) => (
            <li key={k} className="rounded-md border border-border px-2.5 py-1.5">
              <div className="flex items-center gap-2">
                <span className="h-2.5 rounded-sm" style={{ width: `${Math.max(2, p.share * 100)}%`, maxWidth: '60%', background: 'var(--series-1)' }} aria-hidden />
                <span className="num text-[12px] text-dim">{fmt(p.samples)} samples · {pct(p.share)}</span>
              </div>
              <ul className="mt-1 flex flex-col">
                {p.frames.map((f, i) => (
                  <li key={i} className={cx('num text-[12px] wrap-anywhere', i === 0 ? 'text-text' : 'text-muted')} style={{ paddingLeft: `${i * 10}px` }} title={f}>
                    {i > 0 && '↳ '}{shortFrame(f)}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}

// ---- allocation ---------------------------------------------------------------------------------

function AllocationSection({ jfr, exactBytes }: { jfr: JfrSummary; exactBytes?: number }) {
  const a = jfr.allocation
  return (
    <div className="flex flex-col gap-3">
      <SectionTitle note={<>
        {fmt(a.samples)} jdk.ObjectAllocationSample events weighing {bytes(a.sampledBytes)} (a statistical estimate of bytes allocated, all threads)
        {exactBytes !== undefined && <>; the measuring thread's exact counter says {bytes(exactBytes)}</>}.
        {a.discardedFirstSamples > 0 && <> {fmt(a.discardedFirstSamples)} first sample(s) per thread left out: their weight reaches back before the recording.</>}
      </>}>
        Allocation
      </SectionTitle>
      {a.samples === 0 ? (
        <p className="text-[12.5px] text-muted">No allocation samples in the measured window.</p>
      ) : (
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-[12.5px]">
              <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">By class</caption>
              <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
                <tr>{['Class', 'Weight', 'Share', 'Samples'].map((h) => <th key={h} scope="col" className={th}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {a.byClass.map((r) => (
                  <tr key={r.objectClass} className="border-t border-border">
                    <td className={td}><Frame frame={r.objectClass} /></td>
                    <td className={cx(td, 'num')}>{bytes(r.weightBytes)}</td>
                    <td className={td}><ShareBar share={r.share} tone="var(--series-2)" /></td>
                    <td className={cx(td, 'num text-muted')}>{fmt(r.samples)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-[12.5px]">
              <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">By allocation site</caption>
              <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
                <tr>{['Class', 'Allocated in', 'From scenario code', 'Share'].map((h) => <th key={h} scope="col" className={th}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {a.sites.map((r, k) => (
                  <tr key={k} className="border-t border-border">
                    <td className={td}><Frame frame={r.objectClass} /></td>
                    <td className={td}><Frame frame={r.topFrame} /></td>
                    <td className={td}>{r.appFrame ? <Frame frame={r.appFrame} /> : <span className="text-muted">— (not in a scenario frame)</span>}</td>
                    <td className={td}><ShareBar share={r.share} tone="var(--series-2)" /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}

// ---- GC -----------------------------------------------------------------------------------------

function GcSection({ jfr }: { jfr: JfrSummary }) {
  const gc = jfr.gc
  const windows = markerWindows(jfr.markers)
  const points = gc.pauses.map((p) => ({ atMs: p.atMs, pauseMs: p.sumOfPausesMs, name: p.name, cause: p.cause }))
  return (
    <div className="flex flex-col gap-2">
      <SectionTitle note={<>
        Collectors: {gc.youngCollector ?? '?'} (young) / {gc.oldCollector ?? '?'} (old) · {fmt(gc.collections)} collection(s), {fmt(gc.totalPauseMs)} ms of pauses in total, longest {fmt(gc.maxPauseMs)} ms (jdk.GarbageCollection).
      </>}>
        GC pauses
      </SectionTitle>
      {gc.collections === 0 ? (
        <p className="text-[12.5px] text-muted">No garbage collection in the measured window.</p>
      ) : (
        <>
          <figure aria-label="GC pause timeline" className="h-48 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <ScatterChart margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
                <CartesianGrid stroke="var(--grid)" strokeDasharray="3 3" />
                {windows.map((w, k) => (
                  <ReferenceArea key={w.name} x1={w.fromMs} x2={w.toMs} fill="var(--series-4)" fillOpacity={k % 2 === 0 ? 0.08 : 0.14} />
                ))}
                <XAxis dataKey="atMs" type="number" domain={['auto', 'auto']} tick={axisTick} stroke="var(--border)" tickFormatter={(v) => `${fmt(Math.round(v))}`} label={{ value: 'ms since recording start', position: 'insideBottomRight', offset: -2, fill: 'var(--muted)', fontSize: 11 }} />
                <YAxis dataKey="pauseMs" type="number" tick={axisTick} stroke="var(--border)" width={56} tickFormatter={(v) => `${fmt(v, 2)}`} label={{ value: 'pause ms', angle: -90, position: 'insideLeft', fill: 'var(--muted)', fontSize: 11 }} />
                <Tooltip contentStyle={tooltipStyle} formatter={(v: number, n: string) => [n === 'pauseMs' ? `${fmt(v)} ms` : `${fmt(Math.round(v))} ms`, n === 'pauseMs' ? 'pause' : 'at']} />
                <Scatter data={points} fill="var(--series-2)" isAnimationActive={false} />
              </ScatterChart>
            </ResponsiveContainer>
          </figure>
          <p className="text-[11.5px] text-muted">Shaded bands: measured trials (from JFR markers).</p>
          <details className="text-[12.5px]">
            <summary className="cursor-pointer text-dim">All {fmt(gc.pauses.length)} listed collections</summary>
            <div className="mt-2 max-h-72 overflow-auto rounded-md border border-border">
              <table className="w-full text-[12px]">
                <thead className="sticky top-0 bg-panel-2 text-left text-muted">
                  <tr>{['GC id', 'Collector', 'Cause', 'At (ms)', 'Pause (ms)', 'Heap before → after'].map((h) => <th key={h} scope="col" className="px-2 py-1 font-medium">{h}</th>)}</tr>
                </thead>
                <tbody>
                  {gc.pauses.map((p) => (
                    <tr key={p.gcId} className="border-t border-border">
                      <td className="num px-2 py-1 text-muted">{p.gcId}</td>
                      <td className="px-2 py-1">{p.name}</td>
                      <td className="px-2 py-1 text-dim">{p.cause}</td>
                      <td className="num px-2 py-1">{fmt(Math.round(p.atMs))}</td>
                      <td className="num px-2 py-1">{fmt(p.sumOfPausesMs)}</td>
                      <td className="num px-2 py-1">{bytes(p.heapBeforeBytes)} → {bytes(p.heapAfterBytes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      )}
    </div>
  )
}

// ---- locks --------------------------------------------------------------------------------------

function LocksSection({ jfr }: { jfr: JfrSummary }) {
  const { monitorEnter, threadPark } = jfr.locks
  return (
    <div className="flex flex-col gap-3">
      <SectionTitle note="Only waits at or above the threshold are recorded, so counts and totals are lower bounds.">Lock contention</SectionTitle>
      <ContentionTable title="synchronized — threads BLOCKED entering a monitor (jdk.JavaMonitorEnter)" c={monitorEnter} />
      <ContentionTable title="java.util.concurrent — threads parked waiting for a lock (jdk.ThreadPark)" c={threadPark} />
    </div>
  )
}

function ContentionTable({ title, c }: { title: string; c: JfrContention }) {
  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="w-full text-[12.5px]">
        <caption className="px-2.5 py-1.5 text-left text-[12px] text-muted">
          {title}: {fmt(c.events)} wait(s) ≥ {Number(c.thresholdMs.toPrecision(3))} ms, {fmt(c.totalMs)} ms in total
        </caption>
        {c.rows.length > 0 && (
          <thead className="bg-panel-2 text-left text-[11px] uppercase tracking-wider text-muted">
            <tr>{['Lock', 'Waiting in', 'Waits', 'Total ms', 'Longest ms', 'Stack top'].map((h) => <th key={h} scope="col" className={th}>{h}</th>)}</tr>
          </thead>
        )}
        <tbody>
          {c.rows.map((r, k) => (
            <tr key={k} className={cx('border-t border-border', !r.appFrame && 'text-muted')}>
              <td className={td}><Frame frame={r.lockClass} /></td>
              <td className={td}>{r.appFrame ? <Frame frame={r.appFrame} /> : '— (JDK / JFR thread)'}</td>
              <td className={cx(td, 'num')}>{fmt(r.events)}</td>
              <td className={cx(td, 'num')}>{fmt(r.totalMs)}</td>
              <td className={cx(td, 'num')}>{fmt(r.maxMs)}</td>
              <td className={td}>
                <details>
                  <summary className="cursor-pointer text-[12px] text-dim">{shortFrame(r.stackTop[0])}</summary>
                  <ul className="mt-1">{r.stackTop.map((f, i) => <li key={i} className="num text-[11.5px] text-muted" title={f}>{shortFrame(f)}</li>)}</ul>
                </details>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
