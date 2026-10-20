import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api, type EvidenceItem, type Finding, type FindingKind, type Investigation } from '../lib/api'
import { relativeTime } from '../lib/format'
import { Badge, Button, Card, textareaClass } from './ui'

const KIND: Record<FindingKind, { heading: string; tone: 'info' | 'warn' | 'accent' | 'ok'; hint: string }> = {
  OBSERVATION: { heading: 'Observations', tone: 'info', hint: 'Stated directly by the evidence.' },
  HYPOTHESIS: { heading: 'Hypotheses', tone: 'accent', hint: 'Possible explanations — not established. Each says what would test it.' },
  CAVEAT: { heading: 'Caveats', tone: 'warn', hint: 'Reasons to distrust or limit a conclusion.' },
  NEXT_EXPERIMENT: { heading: 'Suggested experiments', tone: 'ok', hint: 'What to run to learn more.' },
}

/** Citation ids in an answer text, e.g. [R3.m.latencyP99Ms], rendered as buttons that reveal the evidence. */
export function splitCitations(text: string): (string | { id: string })[] {
  const out: (string | { id: string })[] = []
  const re = /\[([A-Za-z][A-Za-z0-9_.-]*)\]/g
  let last = 0
  for (let m = re.exec(text); m; m = re.exec(text)) {
    if (m.index > last) out.push(text.slice(last, m.index))
    out.push({ id: m[1] })
    last = m.index + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

function Cite({ id, known, onOpen }: { id: string; known: boolean; onOpen: (id: string) => void }) {
  return (
    <button type="button" onClick={() => onOpen(id)} disabled={!known}
            title={known ? `Show evidence ${id}` : `${id} is not in the evidence`}
            className={known ? 'num mx-0.5 rounded border border-border bg-panel-2 px-1 text-[11px] text-accent hover:border-accent'
              : 'num mx-0.5 rounded border border-bad px-1 text-[11px] text-bad line-through'}>
      {id}
    </button>
  )
}

function CitedText({ text, ids, onOpen }: { text: string; ids: Set<string>; onOpen: (id: string) => void }) {
  return <>{splitCitations(text).map((p, i) => (typeof p === 'string' ? <span key={i}>{p}</span> : <Cite key={i} id={p.id} known={ids.has(p.id)} onOpen={onOpen} />))}</>
}

function AnswerView({ inv, ids, onOpen }: { inv: Investigation; ids: Set<string>; onOpen: (id: string) => void }) {
  const c = inv.check
  return (
    <article className="flex flex-col gap-2 rounded-md border border-border p-3">
      <header className="flex flex-wrap items-center gap-2 text-[12px] text-muted">
        <span className="font-medium text-text">{inv.question}</span>
        <Badge tone={inv.provider === 'rules' ? 'neutral' : 'accent'}>{inv.provider === 'rules' ? 'rule-based' : `${inv.provider} · ${inv.model}`}</Badge>
        <span>{relativeTime(inv.createdAt)}</span>
      </header>
      <div className="whitespace-pre-wrap text-[13px] leading-relaxed text-dim">
        <CitedText text={inv.answer} ids={ids} onOpen={onOpen} />
      </div>
      {inv.provider !== 'rules' && (
        <ul aria-label="Answer check" className="flex flex-col gap-1 text-[12px]">
          {c.unknownCitations.length > 0 && <li className="text-bad">Cites evidence that does not exist: {c.unknownCitations.join(', ')}.</li>}
          {c.unverifiedNumbers.length > 0 && <li className="text-warn">Numbers not found in the evidence — derived or invented, check them: {c.unverifiedNumbers.join(', ')}.</li>}
          {c.uncited && <li className="text-warn">The answer cites no evidence.</li>}
          {c.unknownCitations.length === 0 && c.unverifiedNumbers.length === 0 && !c.uncited && (
            <li className="text-ok">All {c.checkedNumbers} checked numbers appear in the evidence and every citation resolves.</li>
          )}
        </ul>
      )}
    </article>
  )
}

function EvidenceList({ items, open, onToggle }: { items: EvidenceItem[]; open: string | null; onToggle: (id: string) => void }) {
  return (
    <ul className="flex flex-col gap-1">
      {items.map((i) => (
        <li key={i.id} id={`evidence-${i.id}`} className={open === i.id ? 'rounded-md border border-accent' : 'rounded-md border border-border'}>
          <button type="button" className="flex w-full items-baseline gap-2 px-2 py-1.5 text-left text-[12.5px]" aria-expanded={open === i.id} onClick={() => onToggle(i.id)}>
            <span className="num w-36 shrink-0 truncate text-accent">{i.id}</span>
            <span className="truncate text-dim">{i.title}</span>
            <span className="ml-auto text-[11px] uppercase tracking-wider text-muted">{i.kind.toLowerCase()}</span>
          </button>
          {open === i.id && <pre className="num whitespace-pre-wrap border-t border-border px-2 py-1.5 text-[12px] text-dim">{i.text}</pre>}
        </li>
      ))}
    </ul>
  )
}

/** Evidence-grounded investigation of one experiment: rule-based findings, the evidence, questions. */
export function InvestigatorPanel({ experimentId, runsKey }: { experimentId: string; runsKey?: string }) {
  const qc = useQueryClient()
  const analysis = useQuery({ queryKey: ['investigation', experimentId, runsKey], queryFn: () => api.investigation(experimentId) })
  const history = useQuery({ queryKey: ['investigations', experimentId], queryFn: () => api.investigations(experimentId) })
  const [question, setQuestion] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const ask = useMutation({
    mutationFn: (q: string) => api.ask(experimentId, q),
    onSuccess: () => {
      setQuestion('')
      qc.invalidateQueries({ queryKey: ['investigations', experimentId] })
    },
  })

  if (analysis.isLoading) return <p className="text-muted">Assembling evidence…</p>
  if (analysis.error || !analysis.data) return <p className="text-bad">Could not load the investigation: {String(analysis.error)}</p>
  const a = analysis.data
  const ids = new Set(a.evidence.map((e) => e.id))
  const reveal = (id: string) => {
    setOpen(id)
    // scrollIntoView is missing in some environments (jsdom); revealing the item must not depend on it
    requestAnimationFrame(() => document.getElementById(`evidence-${id}`)?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }))
  }
  const byKind = (k: FindingKind) => a.findings.filter((f: Finding) => f.kind === k)

  return (
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
      <div className="flex flex-col gap-5">
        <Card title="Ask about this experiment">
          <p className="mb-2 text-[12.5px] text-dim">{a.provider.description}</p>
          <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); if (question.trim()) ask.mutate(question.trim()) }}>
            <label htmlFor="investigator-question" className="sr-only">Question</label>
            <textarea id="investigator-question" className={textareaClass} rows={3} maxLength={2000} value={question}
                      onChange={(e) => setQuestion(e.target.value)}
                      placeholder="Why is p99 so much worse than p50? Is this difference statistically convincing? What should I run next?" />
            <div className="flex items-center gap-3">
              <Button variant="primary" type="submit" disabled={!question.trim() || ask.isPending}>
                {ask.isPending ? 'Thinking…' : a.provider.enabled ? `Ask ${a.provider.model}` : 'Ask (rule-based)'}
              </Button>
              {a.provider.sendsEvidenceOffHost && <span className="text-[11.5px] text-warn">Sends the evidence listed on the right to {a.provider.provider}.</span>}
              {ask.error && <span className="text-[12px] text-bad">{ask.error instanceof ApiError ? ask.error.message : String(ask.error)}</span>}
            </div>
          </form>
          <div className="mt-4 flex flex-col gap-3">
            {(history.data ?? []).map((inv) => <AnswerView key={inv.id} inv={inv} ids={ids} onOpen={reveal} />)}
          </div>
        </Card>

        <Card title="Rule-based findings">
          <p className="mb-3 text-[12px] text-muted">Produced by explicit rules over the stored evidence — no model involved. Each statement names its rule and cites what it rests on.</p>
          {a.findings.length === 0 && <p className="text-[13px] text-muted">No rule applies yet.</p>}
          {(Object.keys(KIND) as FindingKind[]).map((k) => byKind(k).length > 0 && (
            <section key={k} className="mb-3">
              <h4 className="mb-1 flex items-center gap-2 text-[12px] font-medium uppercase tracking-wider text-muted">
                <Badge tone={KIND[k].tone}>{KIND[k].heading}</Badge><span className="normal-case tracking-normal">{KIND[k].hint}</span>
              </h4>
              <ul className="flex flex-col gap-1.5">
                {byKind(k).map((f, i) => (
                  <li key={`${f.rule}-${i}`} className="text-[13px] text-dim">
                    {f.text}{' '}
                    {f.citations.map((c) => <Cite key={c} id={c} known={ids.has(c)} onOpen={reveal} />)}
                    <span className="num ml-1 text-[11px] text-muted">({f.rule})</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </Card>
      </div>

      <Card title={`Evidence (${a.evidence.length} items)`}>
        <p className="mb-2 text-[12px] text-muted">Everything the investigator — and any model — is allowed to use, read from stored runs. Numbers are rounded to 4 significant digits.</p>
        <EvidenceList items={a.evidence} open={open} onToggle={(id) => setOpen(open === id ? null : id)} />
      </Card>
    </div>
  )
}
