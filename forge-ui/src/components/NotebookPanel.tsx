import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { Experiment } from '../lib/api'
import { api } from '../lib/api'
import { Field, textareaClass } from './ui'

type Key = 'observations' | 'alternativeExplanations' | 'decision' | 'followUps'

const FIELDS: { key: Key; label: string; hint: string }[] = [
  { key: 'observations', label: 'Observations', hint: 'What did you see? Quote numbers from the runs, with run numbers.' },
  { key: 'alternativeExplanations', label: 'Alternative explanations', hint: 'What else could produce the same data? (JIT, GC, frequency scaling, contention, measurement overhead…)' },
  { key: 'decision', label: 'Decision', hint: 'What will you do, and what evidence would change your mind?' },
  { key: 'followUps', label: 'Follow-up experiments', hint: 'The next runs that would reduce uncertainty the most.' },
]

/** Notebook fields save on blur; nothing is lost on navigation because each field saves independently. */
export function NotebookPanel({ experiment }: { experiment: Experiment }) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState<Record<Key, string>>(() => pick(experiment))
  const [saved, setSaved] = useState<Key | null>(null)
  useEffect(() => setDraft(pick(experiment)), [experiment])

  const save = useMutation({
    mutationFn: (fields: Partial<Experiment>) => api.updateExperiment(experiment.id, fields),
    onSuccess: (_, fields) => {
      qc.invalidateQueries({ queryKey: ['experiment', experiment.id] })
      qc.invalidateQueries({ queryKey: ['review', experiment.id] })
      setSaved(Object.keys(fields)[0] as Key)
      setTimeout(() => setSaved(null), 1500)
    },
  })

  return (
    <div className="flex flex-col gap-4">
      {FIELDS.map((f) => (
        <Field key={f.key} label={f.label} htmlFor={`nb-${f.key}`} hint={saved === f.key ? 'Saved.' : f.hint}>
          <textarea
            id={`nb-${f.key}`}
            rows={4}
            className={textareaClass}
            value={draft[f.key]}
            onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
            onBlur={() => draft[f.key] !== experiment[f.key] && save.mutate({ [f.key]: draft[f.key] })}
          />
        </Field>
      ))}
      {save.isError && <p role="alert" className="text-[13px] text-bad">Could not save: {(save.error as Error).message}</p>}
    </div>
  )
}

function pick(e: Experiment): Record<Key, string> {
  return { observations: e.observations, alternativeExplanations: e.alternativeExplanations, decision: e.decision, followUps: e.followUps }
}
