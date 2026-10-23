import { useQuery } from '@tanstack/react-query'
import {
  Activity, BookOpen, Bot, Boxes, Cpu, Database, FlaskConical, Gauge, GitBranch, Moon, Search, Sun, Swords, Zap,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { api } from '../lib/api'
import { MODULES } from '../lib/labs'
import { useTheme } from '../lib/theme'
import { CommandPalette } from './CommandPalette'
import { cx, Kbd } from './ui'

const ICONS: Record<string, ReactNode> = {
  '/': <Gauge size={16} />,
  '/experiments': <FlaskConical size={16} />,
  '/labs/jvm': <Cpu size={16} />,
  '/labs/concurrency': <GitBranch size={16} />,
  '/labs/database': <Database size={16} />,
  '/labs/failure': <Zap size={16} />,
  '/labs/algorithms': <Boxes size={16} />,
  '/challenges': <Swords size={16} />,
  '/investigator': <Bot size={16} />,
  '/notebook': <BookOpen size={16} />,
}

export function Layout() {
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  const [theme, toggleTheme] = useTheme()
  const navigate = useNavigate()
  const pendingG = useRef(0)
  const system = useQuery({ queryKey: ['system'], queryFn: api.system, refetchInterval: 5000 })

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null
      const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((o) => !o)
        return
      }
      if (typing || e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === '?') {
        setHelpOpen((o) => !o)
        return
      }
      if (e.key === 'Escape') {
        setHelpOpen(false)
        return
      }
      const now = Date.now()
      if (e.key === 'g') {
        pendingG.current = now
        return
      }
      if (now - pendingG.current < 1200) {
        const m = MODULES.find((x) => x.shortcut === `g ${e.key}`)
        if (m) {
          e.preventDefault()
          navigate(m.path)
        }
        pendingG.current = 0
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [navigate])

  const pending = system.data?.pendingRuns ?? 0

  return (
    <div className="flex h-full">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:m-2 focus:rounded focus:bg-panel focus:px-3 focus:py-2">
        Skip to content
      </a>
      <aside className="no-print hidden md:flex w-60 shrink-0 flex-col border-r border-border bg-bg-elev">
        <div className="flex items-center gap-2.5 px-4 h-14 border-b border-border">
          <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
            <rect width="32" height="32" rx="7" fill="var(--panel-2)" />
            <path d="M9 23V9h14M9 16h10" stroke="var(--accent)" strokeWidth="3" fill="none" strokeLinecap="round" />
          </svg>
          <div className="leading-tight">
            <div className="font-semibold tracking-[0.18em] text-[13px]">FORGE</div>
            <div className="text-[11px] text-muted">engineering laboratory</div>
          </div>
        </div>
        <nav aria-label="Primary" className="flex-1 overflow-y-auto p-2">
          <ul className="flex flex-col gap-0.5">
            {MODULES.map((m) => (
              <li key={m.path}>
                <NavLink
                  to={m.path}
                  end={m.path === '/'}
                  className={({ isActive }) =>
                    cx(
                      'group flex items-center gap-2.5 rounded-md px-2.5 h-8 text-[13px] transition-colors',
                      isActive ? 'bg-panel-2 text-text' : 'text-dim hover:bg-panel hover:text-text',
                    )
                  }
                >
                  {({ isActive }) => (
                    <>
                      <span className={isActive ? 'text-accent' : 'text-muted'}>{ICONS[m.path]}</span>
                      <span className="flex-1 truncate">{m.label}</span>
                    </>
                  )}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="border-t border-border p-3 text-[12px] text-muted flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <Activity size={13} className={pending > 0 ? 'text-info' : 'text-muted'} />
            <span aria-live="polite">{system.isError ? 'API unreachable' : pending > 0 ? `${pending} run(s) queued or running` : 'runner idle'}</span>
          </div>
          <div className="truncate" title={String(system.data?.host.cpuModel ?? '')}>{String(system.data?.host.cpuModel ?? '')}</div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="no-print flex items-center gap-3 border-b border-border bg-bg-elev px-4 h-14">
          <button
            onClick={() => setPaletteOpen(true)}
            className="flex h-8 w-full max-w-md items-center gap-2 rounded-md border border-border bg-bg px-2.5 text-[13px] text-muted hover:border-border-strong"
            aria-label="Open command palette"
          >
            <Search size={14} />
            <span className="flex-1 text-left">Search experiments, labs, actions…</span>
            <Kbd>Ctrl K</Kbd>
          </button>
          <div className="flex-1" />
          <button onClick={() => setHelpOpen(true)} className="text-[12px] text-muted hover:text-text" aria-label="Keyboard shortcuts">
            <Kbd>?</Kbd>
          </button>
          <button
            onClick={toggleTheme}
            className="flex size-8 items-center justify-center rounded-md text-dim hover:bg-panel-2 hover:text-text"
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          >
            {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
          </button>
        </header>
        <main id="main" className="min-h-0 flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} onToggleTheme={toggleTheme} />
      {helpOpen && <ShortcutHelp onClose={() => setHelpOpen(false)} />}
    </div>
  )
}

function ShortcutHelp({ onClose }: { onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label="Keyboard shortcuts" className="w-full max-w-md rounded-lg border border-border bg-panel p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <h2 className="mb-3 font-semibold">Keyboard shortcuts</h2>
        <ul className="flex flex-col gap-2 text-[13px]">
          <li className="flex justify-between"><span>Command palette</span><Kbd>Ctrl / ⌘ K</Kbd></li>
          <li className="flex justify-between"><span>This help</span><Kbd>?</Kbd></li>
          {MODULES.map((m) => (
            <li key={m.path} className="flex justify-between"><span>Go to {m.label}</span><Kbd>{m.shortcut}</Kbd></li>
          ))}
        </ul>
        <p className="mt-4 text-[12px] text-muted">Press Esc to close.</p>
      </div>
    </div>
  )
}
