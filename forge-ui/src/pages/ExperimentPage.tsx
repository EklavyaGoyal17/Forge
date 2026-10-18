import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, Download, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ComparePanel } from '../components/ComparePanel'
import { NotebookPanel } from '../components/NotebookPanel'
import { ReviewPanel } from '../components/ReviewPanel'
import { RunConfigForm } from '../components/RunConfigForm'
import { RunDetail } from '../components/RunDetail'
import { Badge, Button, Card, EmptyState, InvariantBadge, Markdownish, StatusPill, Tabs, cx } from '../components/ui'
import type { CreateRunRequest, Experiment, ExperimentStatus, Run, ScenarioDescriptor } from '../lib/api'
import { ApiError, api } from '../lib/api'
import { duration, fmt, relativeTime, runDuration } from '../lib/format'
import { LAB_LABEL } from '../lib/labs'

type Tab = 'runs' | 'compare' | 'notebook' | 'review' | 'report' | 'scenario'

export function ExperimentPage() {
  const { id = '' } = useParams()
  const [search, setSearch] = useSearchParams()
  const tab = (search.get('tab') as Tab) || 'runs'
  const setTab = (t: Tab) => setSearch((s) => { s.set('tab', t); return s }, { replace: true })
  const qc = useQueryClient()

  const exp = useQuery({
    queryKey: ['experiment', id],
    queryFn: () => api.experiment(id),
    refetchInterval: (q) => (q.state.data?.runs.some((r) => r.status === 'QUEUED' || r.status === 'RUNNING') ? 1500 : false),
  })
  const scenario = useQuery({
    queryKey: ['scenario', exp.data?.experiment.scenarioId],
    queryFn: () => api.scenario(exp.data!.experiment.scenarioId),
    enabled: Boolean(exp.data),
    staleTime: Infinity,
  })

  if (exp.isLoading) return <p className="p-6 text-muted">Loading experiment…</p>
  if (exp.isError || !exp.data) return <EmptyState title="Experiment not found">It may have been deleted. <Link className="text-accent" to="/experiments">All experiments</Link></EmptyState>
  const { experiment, runs } = exp.data
  const d = scenario.data?.descriptor
  const runsKey = runs.map((r) => `${r.id}:${r.status}`).join(',')

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-5 p-6">
      <Header experiment={experiment} descriptor={d} onChanged={() => qc.invalidateQueries({ queryKey: ['experiment', id] })} hasActive={runs.some((r) => r.status === 'QUEUED' || r.status === 'RUNNING')} />

      <Tabs<Tab>
        label="Experiment sections"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'runs', label: `Runs (${runs.length})` },
          { id: 'compare', label: 'Compare' },
          { id: 'notebook', label: 'Notebook' },
          { id: 'review', label: 'Review board' },
          { id: 'report', label: 'Report' },
          { id: 'scenario', label: 'About this scenario' },
        ]}
      />

      {!d ? (
        <p className="text-muted">Loading scenario…</p>
      ) : (
        <>
          {tab === 'runs' && <RunsWorkbench experiment={experiment} runs={runs} descriptor={d} sources={scenario.data!.sources} />}
          {tab === 'compare' && <Card><ComparePanel runs={runs} /></Card>}
          {tab === 'notebook' && <Card><NotebookPanel experiment={experiment} /></Card>}
          {tab === 'review' && <Card><ReviewPanel experiment={experiment} runsKey={runsKey} /></Card>}
          {tab === 'report' && <ReportView experiment={experiment} runsKey={runsKey} />}
          {tab === 'scenario' && <ScenarioAbout descriptor={d} />}
        </>
      )}
    </div>
  )
}

function Header({ experiment, descriptor, onChanged, hasActive }: { experiment: Experiment; descriptor?: ScenarioDescriptor; onChanged: () => void; hasActive: boolean }) {
  const navigate = useNavigate()
  const [hypOpen, setHypOpen] = useState(true)
  const update = useMutation({ mutationFn: (f: Partial<Experiment>) => api.updateExperiment(experiment.id, f), onSuccess: onChanged })
  const del = useMutation({ mutationFn: () => api.deleteExperiment(experiment.id), onSuccess: () => navigate('/experiments') })

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 text-[12px] text-muted">
            {descriptor && <Badge tone="accent">{LAB_LABEL[descriptor.lab]}</Badge>}
            <span className="num">{experiment.scenarioId}</span>
            <span>· created {relativeTime(experiment.createdAt)}</span>
            {experiment.tags.map((t) => <Badge key={t}>{t}</Badge>)}
          </div>
          <h1 className="mt-1 text-[22px] font-semibold tracking-tight">{experiment.title}</h1>
        </div>
        <select
          aria-label="Experiment status"
          className="h-8 rounded-md border border-border bg-bg-elev px-2 text-[13px]"
          value={experiment.status}
          onChange={(e) => update.mutate({ status: e.target.value as ExperimentStatus })}
        >
          <option value="OPEN">Open</option>
          <option value="CONCLUDED">Concluded</option>
          <option value="ARCHIVED">Archived</option>
        </select>
        <Button
          variant="ghost"
          aria-label="Delete experiment"
          title={hasActive ? 'Cancel running runs first' : 'Delete experiment'}
          disabled={hasActive || del.isPending}
          onClick={() => {
            if (window.confirm(`Delete “${experiment.title}” and all of its runs, events and artifacts? This cannot be undone.`)) del.mutate()
          }}
        >
          <Trash2 size={14} />
        </Button>
      </div>
      <div className="rounded-lg border border-border bg-panel">
        <button className="flex w-full items-center gap-2 px-4 h-10 text-left text-[12px] font-semibold uppercase tracking-wide text-dim" onClick={() => setHypOpen((o) => !o)} aria-expanded={hypOpen}>
          {hypOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />} Question & hypothesis
        </button>
        {hypOpen && (
          <div className="grid gap-4 border-t border-border p-4 md:grid-cols-2">
            <div>
              <h3 className="mb-1 text-[11px] uppercase tracking-wider text-muted">Engineering question</h3>
              <p className="leading-relaxed text-dim">{experiment.question || <span className="text-muted">Not recorded.</span>}</p>
            </div>
            <div>
              <h3 className="mb-1 text-[11px] uppercase tracking-wider text-muted">Hypothesis <span className="normal-case tracking-normal">· fixed {relativeTime(experiment.hypothesisUpdatedAt)}</span></h3>
              <p className="leading-relaxed text-text">{experiment.hypothesis}</p>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function RunsWorkbench({ experiment, runs, descriptor, sources }: { experiment: Experiment; runs: Run[]; descriptor: ScenarioDescriptor; sources: Record<string, string> }) {
  const qc = useQueryClient()
  const [selected, setSelected] = useState<string | undefined>(runs.at(-1)?.id)
  const [configOpen, setConfigOpen] = useState(runs.length === 0)
  const lastRun = runs.at(-1)
  useEffect(() => {
    if (!selected && runs.length) setSelected(runs.at(-1)!.id)
  }, [runs, selected])

  const create = useMutation({
    mutationFn: (req: CreateRunRequest) => api.createRun(experiment.id, req),
    onSuccess: (run) => {
      qc.invalidateQueries({ queryKey: ['experiment', experiment.id] })
      qc.invalidateQueries({ queryKey: ['system'] })
      setSelected(run.id)
      setConfigOpen(false)
    },
  })
  const serverErrors = create.error instanceof ApiError ? create.error.fieldErrors : undefined

  return (
    <div className="grid gap-5 xl:grid-cols-[minmax(420px,0.85fr)_1.4fr]">
      <div className="flex flex-col gap-5">
        <Card
          title="Runs"
          actions={<Button variant={configOpen ? 'secondary' : 'primary'} onClick={() => setConfigOpen((o) => !o)}>{configOpen ? 'Close' : 'New run'}</Button>}
          dense
        >
          {configOpen && (
            <div className="border-b border-border p-4">
              <RunConfigForm
                key={lastRun?.id ?? 'first'}
                descriptor={descriptor}
                sources={sources}
                lastRun={lastRun}
                onSubmit={(r) => create.mutate(r)}
                submitting={create.isPending}
                serverErrors={serverErrors}
              />
              {create.isError && !serverErrors?.length && <p role="alert" className="mt-2 text-[13px] text-bad">{(create.error as Error).message}</p>}
            </div>
          )}
          {runs.length === 0 ? (
            <EmptyState title="No runs yet">
              Start with a <strong>baseline</strong>: the implementation you want to improve. Then run the candidate with identical parameters so that the variant is the only difference.
            </EmptyState>
          ) : (
            <RunList runs={runs} descriptor={descriptor} selected={selected} onSelect={setSelected} />
          )}
        </Card>
      </div>
      <Card title={selected ? 'Evidence' : undefined}>
        {selected ? <RunDetail key={selected} runId={selected} descriptor={descriptor} /> : <EmptyState title="Select a run">Each run's measurements, timeline, raw artifacts and environment appear here.</EmptyState>}
      </Card>
    </div>
  )
}

function RunList({ runs, descriptor, selected, onSelect }: { runs: Run[]; descriptor: ScenarioDescriptor; selected?: string; onSelect: (id: string) => void }) {
  const primary = descriptor.metrics.find((m) => m.name === descriptor.primaryMetric)
  const rows = useMemo(() => [...runs].reverse(), [runs])
  return (
    <ul className="divide-y divide-border" aria-label="Runs">
      {rows.map((r) => {
        const values = r.result?.trials.map((t) => t.metrics[descriptor.primaryMetric]).filter((v): v is number => typeof v === 'number') ?? []
        const sorted = [...values].sort((a, b) => a - b)
        const median = sorted.length ? (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.ceil((sorted.length - 1) / 2)]) / 2 : undefined
        const explore = r.params.mode === 'explore'
        return (
          <li key={r.id}>
            <button
              onClick={() => onSelect(r.id)}
              aria-current={selected === r.id}
              className={cx('grid w-full grid-cols-[2.2rem_1fr_auto] items-center gap-3 px-4 py-2.5 text-left transition-colors', selected === r.id ? 'bg-panel-2' : 'hover:bg-panel-2/60')}
            >
              <span className="num text-[12px] text-muted">#{r.seq}</span>
              <span className="min-w-0">
                <span className="flex items-center gap-2">
                  <span className="num truncate text-[13px]">{r.variant}</span>
                  <Badge tone={r.role === 'BASELINE' ? 'info' : r.role === 'CANDIDATE' ? 'accent' : 'neutral'}>{r.role.toLowerCase()}</Badge>
                  {explore && <Badge>explore</Badge>}
                </span>
                <span className="mt-0.5 flex items-center gap-2 text-[12px] text-muted">
                  {r.status === 'SUCCEEDED' && !explore && median !== undefined && primary && <span className="num">{fmt(median)} {primary.unit} median</span>}
                  {r.status === 'SUCCEEDED' && explore && r.result?.details.explorer && <span className="num">{r.result.details.explorer.violations}/{r.result.details.explorer.schedulesExplored} schedules violate</span>}
                  <span>{duration(runDuration(r.startedAt, r.finishedAt))}</span>
                  <span>{relativeTime(r.queuedAt)}</span>
                </span>
              </span>
              <span className="flex flex-col items-end gap-1">
                <StatusPill status={r.status} />
                {r.status === 'SUCCEEDED' && r.result?.invariant.held !== null && <InvariantBadge held={r.result?.invariant.held} />}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

function ReportView({ experiment, runsKey }: { experiment: Experiment; runsKey: string }) {
  const report = useQuery({ queryKey: ['report', experiment.id, runsKey, experiment.updatedAt], queryFn: () => api.report(experiment.id) })
  function download() {
    if (!report.data) return
    const url = URL.createObjectURL(new Blob([report.data], { type: 'text/markdown;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `forge-${experiment.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')}.md`
    a.click()
    URL.revokeObjectURL(url)
  }
  return (
    <Card title="Engineering report (Markdown)" actions={<><Button onClick={() => window.print()} className="no-print">Print / PDF</Button><Button variant="primary" onClick={download} disabled={!report.data}><Download size={14} /> Download .md</Button></>}>
      <p className="no-print mb-3 text-[12px] text-muted">Generated from the stored runs and notebook. PDF export uses your browser's print dialog (“Save as PDF”).</p>
      <pre className="num max-h-[70vh] overflow-auto whitespace-pre-wrap rounded-md border border-border bg-code p-4 text-[12.5px] leading-relaxed">{report.data ?? 'Generating…'}</pre>
    </Card>
  )
}

function ScenarioAbout({ descriptor: d }: { descriptor: ScenarioDescriptor }) {
  return (
    <div className="grid gap-5 lg:grid-cols-[1.4fr_1fr]">
      <Card title={d.title}>
        <div className="flex flex-col gap-4">
          <p className="text-dim">{d.summary}</p>
          <Markdownish text={d.explanation} />
          {d.invariant && <p className="text-[13px]"><span className="text-muted">Invariant: </span>{d.invariant}</p>}
        </div>
      </Card>
      <div className="flex flex-col gap-5">
        <Card title="Limits of this benchmark">
          <ul className="flex list-disc flex-col gap-2 pl-4 text-[13px] leading-relaxed text-dim">
            {d.caveats.map((c) => <li key={c}>{c}</li>)}
          </ul>
        </Card>
        <Card title="Metrics">
          <dl className="flex flex-col gap-2 text-[13px]">
            {d.metrics.map((m) => (
              <div key={m.name}>
                <dt className="font-medium">{m.label} {m.unit && <span className="text-muted">({m.unit})</span>} {m.name === d.primaryMetric && <Badge tone="accent">primary</Badge>}</dt>
                <dd className="text-dim">{m.description}</dd>
              </div>
            ))}
          </dl>
        </Card>
      </div>
    </div>
  )
}
