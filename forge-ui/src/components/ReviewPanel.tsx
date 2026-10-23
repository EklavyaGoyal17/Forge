import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { Experiment } from '../lib/api'
import { api } from '../lib/api'
import { titleCase } from '../lib/format'
import { Badge, CHECK_TONE, textareaClass } from './ui'

export function ReviewPanel({ experiment, runsKey }: { experiment: Experiment; runsKey: string }) {
  const qc = useQueryClient()
  const review = useQuery({ queryKey: ['review', experiment.id, runsKey], queryFn: () => api.review(experiment.id) })
  const [answers, setAnswers] = useState<Record<string, string>>(experiment.reviewAnswers ?? {})
  useEffect(() => setAnswers(experiment.reviewAnswers ?? {}), [experiment.reviewAnswers])
  const save = useMutation({
    mutationFn: (a: Record<string, string>) => api.updateExperiment(experiment.id, { reviewAnswers: a }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['experiment', experiment.id] }),
  })

  if (!review.data) return <p className="text-muted">Evaluating rubric…</p>
  const r = review.data
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <div className="flex items-baseline gap-3">
          <span className="num text-[28px] font-semibold">{r.passed}<span className="text-muted">/{r.applicable}</span></span>
          <span className="text-dim">evidence checks passed</span>
        </div>
        <p className="text-[12px] text-muted">{r.rubricNote}</p>
      </div>

      <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
        {r.checks.map((c) => (
          <li key={c.id} className="grid grid-cols-1 gap-1 p-3 sm:grid-cols-[6.5rem_1fr]">
            <span><Badge tone={CHECK_TONE[c.status]}>{titleCase(c.status)}</Badge></span>
            <div>
              <p className="text-[13px] font-medium">{c.criterion}</p>
              <p className="text-[12.5px] leading-relaxed text-dim">{c.evidence}</p>
            </div>
          </li>
        ))}
      </ul>

      <div className="flex flex-col gap-4">
        <div>
          <h4 className="font-medium">Questions a staff engineer would ask</h4>
          <p className="text-[12px] text-muted">Answers are saved with the experiment and included in the report. They are not scored.</p>
        </div>
        {r.questions.map((q) => (
          <div key={q.id} className="flex flex-col gap-1">
            <label htmlFor={`q-${q.id}`} className="text-[13px] font-medium">{q.text}</label>
            <span className="text-[12px] text-muted">{q.hint}</span>
            <textarea
              id={`q-${q.id}`}
              rows={3}
              className={textareaClass}
              value={answers[q.id] ?? ''}
              onChange={(e) => setAnswers((a) => ({ ...a, [q.id]: e.target.value }))}
              onBlur={() => (answers[q.id] ?? '') !== (experiment.reviewAnswers?.[q.id] ?? '') && save.mutate(answers)}
            />
          </div>
        ))}
      </div>
    </div>
  )
}
