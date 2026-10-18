import { useQuery } from '@tanstack/react-query'
import { Command } from 'cmdk'
import { FlaskConical, MoonStar, Plus } from 'lucide-react'
import { useNavigate } from 'react-router'
import { api } from '../lib/api'
import { MODULES } from '../lib/labs'

export function CommandPalette({ open, onClose, onToggleTheme }: { open: boolean; onClose: () => void; onToggleTheme: () => void }) {
  const navigate = useNavigate()
  const experiments = useQuery({ queryKey: ['experiments', ''], queryFn: () => api.experiments(), enabled: open })
  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: api.scenarios, enabled: open })

  function go(path: string) {
    navigate(path)
    onClose()
  }

  return (
    <Command.Dialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      label="Command palette"
      className="fixed left-1/2 top-[15vh] z-50 w-[min(640px,92vw)] -translate-x-1/2 overflow-hidden rounded-lg border border-border bg-panel shadow-2xl"
      overlayClassName="fixed inset-0 z-40 bg-black/50"
    >
      <Command.Input
        placeholder="Type a command or search…"
        className="h-12 w-full border-b border-border bg-transparent px-4 text-[14px] text-text placeholder:text-muted outline-none"
      />
      <Command.List className="max-h-[50vh] overflow-y-auto p-2">
        <Command.Empty className="px-3 py-6 text-center text-muted">No results.</Command.Empty>
        <Command.Group heading="Actions" className="text-[11px] text-muted [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5">
          <Item onSelect={() => go('/experiments/new')} icon={<Plus size={14} />}>New experiment</Item>
          <Item onSelect={() => { onToggleTheme(); onClose() }} icon={<MoonStar size={14} />}>Toggle light / dark theme</Item>
        </Command.Group>
        <Command.Group heading="Navigate" className="text-[11px] text-muted [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5">
          {MODULES.map((m) => (
            <Item key={m.path} onSelect={() => go(m.path)} hint={m.shortcut}>
              {m.label}{!m.implemented && ` — planned (phase ${m.phase})`}
            </Item>
          ))}
        </Command.Group>
        {scenarios.data && (
          <Command.Group heading="Start from a scenario" className="text-[11px] text-muted [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5">
            {scenarios.data.map((s) => (
              <Item key={s.id} onSelect={() => go(`/experiments/new?scenario=${encodeURIComponent(s.id)}`)} icon={<Plus size={14} />}>
                {s.title}
              </Item>
            ))}
          </Command.Group>
        )}
        {experiments.data && experiments.data.length > 0 && (
          <Command.Group heading="Experiments" className="text-[11px] text-muted [&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5">
            {experiments.data.slice(0, 50).map((e) => (
              <Item key={e.experiment.id} value={`${e.experiment.title} ${e.experiment.scenarioId}`} onSelect={() => go(`/experiments/${e.experiment.id}`)} icon={<FlaskConical size={14} />}>
                {e.experiment.title}
              </Item>
            ))}
          </Command.Group>
        )}
      </Command.List>
    </Command.Dialog>
  )
}

function Item({ children, onSelect, icon, hint, value }: { children: React.ReactNode; onSelect: () => void; icon?: React.ReactNode; hint?: string; value?: string }) {
  return (
    <Command.Item
      value={value}
      onSelect={onSelect}
      className="flex h-9 cursor-pointer items-center gap-2.5 rounded-md px-2.5 text-[13px] text-text"
    >
      <span className="text-muted">{icon}</span>
      <span className="flex-1 truncate">{children}</span>
      {hint && <span className="num text-[11px] text-muted">{hint}</span>}
    </Command.Item>
  )
}
