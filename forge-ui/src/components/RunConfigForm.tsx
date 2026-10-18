import { Play } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { CreateRunRequest, FieldError, ParameterSpec, Run, RunRole, ScenarioDescriptor } from '../lib/api'
import { Badge, Button, CodeBlock, Field, cx, inputClass } from './ui'

type Values = Record<string, number | string | boolean>

/** Parameters relevant for the currently selected mode. */
export function visibleParameters(d: ScenarioDescriptor, values: Values): ParameterSpec[] {
  const mode = values.mode
  return d.parameters.filter((p) => p.name !== 'mode' && (p.appliesToModes.length === 0 || p.appliesToModes.includes(String(mode))))
}

/** Client-side mirror of the server's bounds checks, for immediate feedback. The server re-validates. */
export function validate(d: ScenarioDescriptor, values: Values, trials: number, warmup: number): FieldError[] {
  const errors: FieldError[] = []
  for (const p of d.parameters) {
    const v = values[p.name]
    if (p.type === 'INTEGER') {
      if (typeof v !== 'number' || !Number.isInteger(v)) errors.push({ field: `params.${p.name}`, message: 'must be a whole number' })
      else if ((p.min !== null && v < p.min) || (p.max !== null && v > p.max)) errors.push({ field: `params.${p.name}`, message: `must be between ${p.min} and ${p.max}` })
    }
  }
  if (!Number.isInteger(trials) || trials < 1 || trials > d.trials.maxTrials) errors.push({ field: 'trials', message: `must be between 1 and ${d.trials.maxTrials}` })
  if (!Number.isInteger(warmup) || warmup < 0 || warmup > d.trials.maxWarmup) errors.push({ field: 'warmupTrials', message: `must be between 0 and ${d.trials.maxWarmup}` })
  return errors
}

function defaults(d: ScenarioDescriptor, from?: Run): Values {
  const v: Values = {}
  for (const p of d.parameters) v[p.name] = from?.params[p.name] ?? p.defaultValue
  return v
}

export function RunConfigForm({ descriptor: d, sources, lastRun, onSubmit, submitting, serverErrors }: {
  descriptor: ScenarioDescriptor
  sources: Record<string, string>
  lastRun?: Run
  onSubmit: (req: CreateRunRequest) => void
  submitting?: boolean
  serverErrors?: FieldError[]
}) {
  const [variant, setVariant] = useState(lastRun?.variant ?? d.variants[0].id)
  const [values, setValues] = useState<Values>(() => defaults(d, lastRun))
  const [trials, setTrials] = useState(lastRun?.trials ?? d.trials.defaultTrials)
  const [warmup, setWarmup] = useState(lastRun?.warmupTrials ?? d.trials.defaultWarmup)
  const [seed, setSeed] = useState<string>(lastRun ? String(lastRun.seed) : '')
  const [role, setRole] = useState<RunRole>(lastRun ? (lastRun.role === 'BASELINE' ? 'CANDIDATE' : lastRun.role) : 'BASELINE')
  const [label, setLabel] = useState('')
  const [heapMb, setHeapMb] = useState<string>('')
  const [timeout, setTimeoutS] = useState<string>('')
  const [touched, setTouched] = useState(false)

  const modeParam = d.parameters.find((p) => p.name === 'mode')
  const visible = useMemo(() => visibleParameters(d, values), [d, values])
  const clientErrors = validate(d, values, trials, warmup)
  const errors = touched ? clientErrors : []
  const errorFor = (field: string) => errors.concat(serverErrors ?? []).find((e) => e.field === field)?.message
  const selected = d.variants.find((v) => v.id === variant)!
  const exploreMode = values.mode === 'explore'

  function submit(e: React.FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (clientErrors.length > 0) return
    const seedNum = seed.trim() === '' ? undefined : Number(seed)
    onSubmit({
      variant,
      params: values,
      trials: exploreMode ? 1 : trials,
      warmupTrials: exploreMode ? 0 : warmup,
      seed: seedNum !== undefined && Number.isSafeInteger(seedNum) ? seedNum : undefined,
      role,
      label: label.trim() || undefined,
      heapMb: heapMb ? Number(heapMb) : undefined,
      timeoutSeconds: timeout ? Number(timeout) : undefined,
    })
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-5" noValidate aria-label="Run configuration">
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-[12px] font-medium text-dim">Variant</legend>
        <div className="grid gap-2 sm:grid-cols-2">
          {d.variants.map((v) => (
            <label
              key={v.id}
              className={cx(
                'flex cursor-pointer flex-col gap-1 rounded-md border p-2.5 transition-colors',
                variant === v.id ? 'border-accent bg-accent-soft' : 'border-border hover:border-border-strong',
              )}
            >
              <span className="flex items-center gap-2">
                <input type="radio" name="variant" value={v.id} checked={variant === v.id} onChange={() => setVariant(v.id)} className="accent-[var(--accent)]" />
                <span className="text-[13px] font-medium">{v.label}</span>
                {v.expectedCorrect === false && <Badge tone="bad">broken reference</Badge>}
                {v.expectedCorrect === true && <Badge tone="ok">expected correct</Badge>}
              </span>
              <span className="pl-5 text-[12px] leading-snug text-muted">{v.description}</span>
            </label>
          ))}
        </div>
      </fieldset>

      {sources[variant] && <CodeBlock code={sources[variant]} caption={`${selected.label} — the exact code that runs`} />}

      {modeParam && (
        <fieldset>
          <legend className="mb-1.5 text-[12px] font-medium text-dim">{modeParam.label}</legend>
          <div role="radiogroup" className="inline-flex rounded-md border border-border p-0.5">
            {modeParam.options.map((o) => (
              <button
                key={o}
                type="button"
                role="radio"
                aria-checked={values.mode === o}
                onClick={() => setValues((v) => ({ ...v, mode: o }))}
                className={cx('h-7 rounded px-3 text-[13px]', values.mode === o ? 'bg-panel-2 text-text' : 'text-muted hover:text-dim')}
              >
                {o}
              </button>
            ))}
          </div>
          <p className="mt-1.5 text-[12px] text-muted">{modeParam.description}</p>
        </fieldset>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        {visible.map((p) => (
          <Field key={p.name} label={`${p.label}${p.unit ? ` (${p.unit})` : ''}`} htmlFor={`p-${p.name}`} hint={`${p.description}${p.type === 'INTEGER' ? ` Range ${p.min?.toLocaleString()}–${p.max?.toLocaleString()}.` : ''}`} error={errorFor(`params.${p.name}`)}>
            {p.type === 'ENUM' ? (
              <select id={`p-${p.name}`} className={inputClass} value={String(values[p.name])} onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))}>
                {p.options.map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
            ) : p.type === 'BOOLEAN' ? (
              <input id={`p-${p.name}`} type="checkbox" checked={Boolean(values[p.name])} onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.checked }))} />
            ) : (
              <input
                id={`p-${p.name}`}
                type="number"
                inputMode="numeric"
                className={cx(inputClass, 'num')}
                min={p.min ?? undefined}
                max={p.max ?? undefined}
                value={Number.isFinite(values[p.name] as number) ? String(values[p.name]) : ''}
                onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value === '' ? Number.NaN : Number(e.target.value) }))}
                aria-invalid={Boolean(errorFor(`params.${p.name}`))}
              />
            )}
          </Field>
        ))}
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        {!exploreMode && (
          <>
            <Field label="Measured trials" htmlFor="trials" hint={`1–${d.trials.maxTrials}. ≥ 5 recommended for comparisons.`} error={errorFor('trials')}>
              <input id="trials" type="number" className={cx(inputClass, 'num')} value={Number.isFinite(trials) ? trials : ''} onChange={(e) => setTrials(e.target.value === '' ? Number.NaN : Number(e.target.value))} />
            </Field>
            <Field label="Warm-up trials" htmlFor="warmup" hint="Executed, then discarded." error={errorFor('warmupTrials')}>
              <input id="warmup" type="number" className={cx(inputClass, 'num')} value={Number.isFinite(warmup) ? warmup : ''} onChange={(e) => setWarmup(e.target.value === '' ? Number.NaN : Number(e.target.value))} />
            </Field>
          </>
        )}
        <Field label="Seed" htmlFor="seed" hint="Blank = random (recorded either way).">
          <input id="seed" className={cx(inputClass, 'num')} value={seed} onChange={(e) => setSeed(e.target.value.replace(/[^0-9]/g, ''))} placeholder="random" />
        </Field>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Role" htmlFor="role" hint="The review board compares the latest baseline with the latest candidate.">
          <select id="role" className={inputClass} value={role} onChange={(e) => setRole(e.target.value as RunRole)}>
            <option value="BASELINE">Baseline</option>
            <option value="CANDIDATE">Candidate</option>
            <option value="EXPLORATORY">Exploratory</option>
          </select>
        </Field>
        <Field label="Label" htmlFor="label" hint="Optional note, e.g. “on battery”.">
          <input id="label" className={inputClass} value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} />
        </Field>
        <details className="sm:col-span-1 text-[12px] text-dim">
          <summary className="cursor-pointer select-none py-1 font-medium">Runner limits</summary>
          <div className="mt-2 flex flex-col gap-3">
            <Field label="Heap (MB)" htmlFor="heap" error={errorFor('heapMb')}>
              <input id="heap" className={cx(inputClass, 'num')} placeholder="server default" value={heapMb} onChange={(e) => setHeapMb(e.target.value.replace(/[^0-9]/g, ''))} />
            </Field>
            <Field label="Timeout (s)" htmlFor="timeout" error={errorFor('timeoutSeconds')}>
              <input id="timeout" className={cx(inputClass, 'num')} placeholder={String(d.defaultTimeoutSeconds)} value={timeout} onChange={(e) => setTimeoutS(e.target.value.replace(/[^0-9]/g, ''))} />
            </Field>
          </div>
        </details>
      </div>

      <div className="flex items-center gap-3">
        <Button type="submit" variant="primary" disabled={submitting}>
          <Play size={14} /> {submitting ? 'Queuing…' : 'Queue run'}
        </Button>
        <span className="text-[12px] text-muted">Runs execute one at a time in an isolated JVM so they do not distort each other.</span>
      </div>
    </form>
  )
}
