import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { useId } from 'react'
import type { CheckStatus, RunStatus, Verdict } from '../lib/api'

export function cx(...parts: (string | false | null | undefined)[]) {
  return parts.filter(Boolean).join(' ')
}

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

export function Button({ variant = 'secondary', className, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      {...rest}
      className={cx(
        'inline-flex items-center justify-center gap-1.5 rounded-md px-3 h-8 text-[13px] font-medium transition-colors',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        variant === 'primary' && 'bg-accent text-accent-ink hover:brightness-110',
        variant === 'secondary' && 'bg-panel-2 text-text border border-border hover:border-border-strong',
        variant === 'ghost' && 'text-dim hover:text-text hover:bg-panel-2',
        variant === 'danger' && 'bg-bad-soft text-bad border border-bad/30 hover:bg-bad/20',
        className,
      )}
    />
  )
}

export function Card({ title, actions, children, className, dense }: {
  title?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  dense?: boolean
}) {
  return (
    <section className={cx('rounded-lg border border-border bg-panel', className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-border px-4 h-11">
          <h2 className="text-[13px] font-semibold tracking-wide text-dim uppercase">{title}</h2>
          <div className="flex items-center gap-2">{actions}</div>
        </header>
      )}
      <div className={dense ? '' : 'p-4'}>{children}</div>
    </section>
  )
}

const STATUS_STYLE: Record<RunStatus, string> = {
  QUEUED: 'bg-panel-2 text-dim border-border',
  RUNNING: 'bg-info-soft text-info border-info/30',
  SUCCEEDED: 'bg-ok-soft text-ok border-ok/30',
  FAILED: 'bg-bad-soft text-bad border-bad/30',
  TIMED_OUT: 'bg-warn-soft text-warn border-warn/30',
  CANCELLED: 'bg-panel-2 text-muted border-border',
}

export function StatusPill({ status }: { status: RunStatus }) {
  return (
    <span className={cx('inline-flex items-center gap-1.5 rounded border px-1.5 h-5 text-[11px] font-semibold tracking-wide', STATUS_STYLE[status])}>
      {status === 'RUNNING' && <span className="size-1.5 rounded-full bg-info animate-pulse" aria-hidden />}
      {status.replace('_', ' ')}
    </span>
  )
}

export function Badge({ tone = 'neutral', children, title }: { tone?: 'neutral' | 'ok' | 'bad' | 'warn' | 'info' | 'accent'; children: ReactNode; title?: string }) {
  const t = {
    neutral: 'bg-panel-2 text-dim border-border',
    ok: 'bg-ok-soft text-ok border-ok/30',
    bad: 'bg-bad-soft text-bad border-bad/30',
    warn: 'bg-warn-soft text-warn border-warn/30',
    info: 'bg-info-soft text-info border-info/30',
    accent: 'bg-accent-soft text-accent border-accent/30',
  }[tone]
  return (
    <span title={title} className={cx('inline-flex items-center rounded border px-1.5 h-5 text-[11px] font-medium whitespace-nowrap', t)}>
      {children}
    </span>
  )
}

export function InvariantBadge({ held }: { held: boolean | null | undefined }) {
  if (held === null || held === undefined) return <Badge>no invariant</Badge>
  return held ? <Badge tone="ok">invariant held</Badge> : <Badge tone="bad">invariant violated</Badge>
}

export const VERDICT_TONE: Record<Verdict, 'ok' | 'bad' | 'neutral' | 'warn' | 'info'> = {
  IMPROVEMENT: 'ok',
  REGRESSION: 'bad',
  NO_DIFFERENCE_DETECTED: 'warn',
  NOT_DIRECTIONAL: 'neutral',
  IDENTICAL: 'neutral',
}

export const CHECK_TONE: Record<CheckStatus, 'ok' | 'bad' | 'warn' | 'neutral'> = {
  PASS: 'ok',
  FAIL: 'bad',
  WARN: 'warn',
  NOT_APPLICABLE: 'neutral',
}

export function EmptyState({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center text-center gap-3 py-12 px-6">
      {icon && <div className="text-muted">{icon}</div>}
      <h3 className="text-[15px] font-semibold">{title}</h3>
      {children && <div className="max-w-lg text-dim leading-relaxed">{children}</div>}
      {action}
    </div>
  )
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="num rounded border border-border bg-panel-2 px-1.5 text-[11px] text-dim">{children}</kbd>
}

export function Field({ label, hint, error, children, htmlFor }: { label: string; hint?: ReactNode; error?: string; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={htmlFor} className="text-[12px] font-medium text-dim">{label}</label>
      {children}
      {error ? <p className="text-[12px] text-bad" role="alert">{error}</p> : hint ? <p className="text-[12px] text-muted leading-snug">{hint}</p> : null}
    </div>
  )
}

export const inputClass =
  'h-8 w-full rounded-md border border-border bg-bg-elev px-2.5 text-[13px] text-text placeholder:text-muted focus:border-accent focus:outline-none'

export const textareaClass =
  'w-full rounded-md border border-border bg-bg-elev px-2.5 py-2 text-[13px] text-text placeholder:text-muted focus:border-accent focus:outline-none leading-relaxed'

export function Tabs<T extends string>({ tabs, value, onChange, label }: { tabs: { id: T; label: ReactNode }[]; value: T; onChange: (v: T) => void; label: string }) {
  const id = useId()
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 border-b border-border">
      {tabs.map((t) => (
        <button
          key={t.id}
          id={`${id}-${t.id}`}
          role="tab"
          aria-selected={value === t.id}
          onClick={() => onChange(t.id)}
          className={cx(
            'relative h-9 px-3 text-[13px] font-medium transition-colors',
            value === t.id ? 'text-text' : 'text-muted hover:text-dim',
          )}
        >
          {t.label}
          {value === t.id && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded bg-accent" />}
        </button>
      ))}
    </div>
  )
}

const JAVA_KEYWORDS = new Set([
  'public', 'private', 'protected', 'static', 'final', 'class', 'interface', 'implements', 'extends', 'return', 'new',
  'if', 'else', 'while', 'for', 'do', 'try', 'finally', 'catch', 'throw', 'throws', 'synchronized', 'volatile', 'long',
  'int', 'boolean', 'void', 'true', 'false', 'null', 'break', 'continue', 'this', 'record', 'switch', 'case', 'default',
])

/** Minimal Java highlighter: keywords, comments, strings, annotations, numbers. Text is never injected as HTML. */
function highlight(line: string): ReactNode[] {
  const out: ReactNode[] = []
  const commentAt = line.indexOf('//')
  const code = commentAt >= 0 ? line.slice(0, commentAt) : line
  const comment = commentAt >= 0 ? line.slice(commentAt) : ''
  const re = /("(?:[^"\\]|\\.)*")|(@\w+)|\b(\d[\d_]*L?)\b|\b([A-Za-z_]\w*)\b/g
  let last = 0
  let m: RegExpExecArray | null
  let k = 0
  while ((m = re.exec(code))) {
    if (m.index > last) out.push(code.slice(last, m.index))
    if (m[1]) out.push(<span key={k++} className="text-ok">{m[1]}</span>)
    else if (m[2]) out.push(<span key={k++} className="text-warn">{m[2]}</span>)
    else if (m[3]) out.push(<span key={k++} className="text-info">{m[3]}</span>)
    else if (m[4] && JAVA_KEYWORDS.has(m[4])) out.push(<span key={k++} className="text-accent">{m[4]}</span>)
    else out.push(m[0])
    last = m.index + m[0].length
  }
  if (last < code.length) out.push(code.slice(last))
  if (comment) out.push(<span key={k++} className="text-muted italic">{comment}</span>)
  return out
}

export function CodeBlock({ code, caption }: { code: string; caption?: string }) {
  const lines = code.replace(/\n$/, '').split('\n')
  return (
    <figure className="rounded-md border border-border bg-code overflow-hidden">
      {caption && <figcaption className="border-b border-border px-3 h-8 flex items-center text-[12px] text-muted">{caption}</figcaption>}
      <pre className="overflow-x-auto py-2 text-[12.5px] leading-[1.6]">
        <code className="num block">
          {lines.map((l, i) => (
            <div key={i} className="flex">
              <span className="select-none w-10 shrink-0 pr-3 text-right text-muted/60">{i + 1}</span>
              <span className="pr-4 whitespace-pre">{highlight(l)}</span>
            </div>
          ))}
        </code>
      </pre>
    </figure>
  )
}

export function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 min-w-0">
      <span className="text-[11px] uppercase tracking-wider text-muted">{label}</span>
      <span className="num text-[15px] text-text truncate">{value}</span>
      {sub && <span className="text-[12px] text-muted truncate">{sub}</span>}
    </div>
  )
}

export function Markdownish({ text }: { text: string }) {
  // Paragraphs and `inline code` / **bold** only; content comes from our own descriptors.
  return (
    <div className="flex flex-col gap-3 text-dim leading-relaxed">
      {text.split(/\n\s*\n/).map((p, i) => (
        <p key={i}>
          {p.split(/(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*)/g).map((part, j) =>
            part.startsWith('`') ? <code key={j} className="num rounded bg-panel-2 px-1 text-[12px] text-text">{part.slice(1, -1)}</code>
              : part.startsWith('**') ? <strong key={j} className="text-text">{part.slice(2, -2)}</strong>
                : part.startsWith('*') && part.length > 2 ? <em key={j}>{part.slice(1, -1)}</em>
                  : part,
          )}
        </p>
      ))}
    </div>
  )
}
