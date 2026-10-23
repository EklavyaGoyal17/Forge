import { useQuery } from '@tanstack/react-query'
import { Activity, Menu, Moon, Search, Sun, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { api } from '../lib/api'
import { MODULES } from '../lib/labs'
import { useTheme } from '../lib/theme'
import { CommandPalette } from './CommandPalette'
import { cx, Kbd } from './ui'

export function Layout() {
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  const [navOpen, setNavOpen] = useState(false)
  const [theme, toggleTheme] = useTheme()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const pendingG = useRef(0)
  const menuButton = useRef<HTMLButtonElement>(null)
  const closeNav = useCallback(() => {
    setNavOpen(false)
    menuButton.current?.focus()
  }, [])

  // the drawer is for getting somewhere: once there, it closes
  useEffect(() => setNavOpen(false), [pathname])

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

  return (
    <div className="flex h-full">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:m-2 focus:rounded focus:bg-panel focus:px-3 focus:py-2">
        Skip to content
      </a>
      <aside className="no-print hidden md:flex w-60 shrink-0 flex-col border-r border-border bg-bg-elev">
        <div className="flex items-center px-4 h-14 border-b border-border"><Brand /></div>
        <NavList label="Primary" />
        <RunnerStatus />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="no-print flex items-center gap-2 border-b border-border bg-bg-elev px-3 sm:gap-3 sm:px-4 h-14">
          <button
            ref={menuButton}
            onClick={() => setNavOpen(true)}
            className="flex size-8 shrink-0 items-center justify-center rounded-md text-dim hover:bg-panel-2 hover:text-text md:hidden"
            aria-label="Open navigation"
            aria-expanded={navOpen}
            aria-controls="mobile-nav"
          >
            <Menu size={18} />
          </button>
          <button
            onClick={() => setPaletteOpen(true)}
            className="flex h-8 min-w-0 w-full max-w-md items-center gap-2 rounded-md border border-border bg-bg px-2.5 text-[13px] text-muted hover:border-border-strong"
            aria-label="Open command palette"
          >
            <Search size={14} className="shrink-0" />
            <span className="flex-1 truncate text-left">Search experiments, labs, actions…</span>
            <span className="hidden sm:inline"><Kbd>Ctrl K</Kbd></span>
          </button>
          <div className="flex-1" />
          <button onClick={() => setHelpOpen(true)} className="hidden text-[12px] text-muted hover:text-text sm:block" aria-label="Keyboard shortcuts">
            <Kbd>?</Kbd>
          </button>
          <button
            onClick={toggleTheme}
            className="flex size-8 shrink-0 items-center justify-center rounded-md text-dim hover:bg-panel-2 hover:text-text"
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
          >
            {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
          </button>
        </header>
        <main id="main" className="min-h-0 flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>

      {navOpen && <MobileNav onClose={closeNav} />}
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} onToggleTheme={toggleTheme} />
      {helpOpen && <ShortcutHelp onClose={() => setHelpOpen(false)} />}
    </div>
  )
}

function Brand() {
  return (
    <div className="flex items-center gap-2.5">
      <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
        <rect width="32" height="32" rx="7" fill="var(--panel-2)" />
        <path d="M9 23V9h14M9 16h10" stroke="var(--accent)" strokeWidth="3" fill="none" strokeLinecap="round" />
      </svg>
      <div className="leading-tight">
        <div className="font-semibold tracking-[0.18em] text-[13px]">FORGE</div>
        <div className="text-[11px] text-muted">engineering laboratory</div>
      </div>
    </div>
  )
}

function NavList({ label }: { label: string }) {
  return (
    <nav aria-label={label} className="flex-1 overflow-y-auto p-2">
      <ul className="flex flex-col gap-0.5">
        {MODULES.map(({ path, label: text, icon: Icon }) => (
          <li key={path}>
            <NavLink
              to={path}
              end={path === '/'}
              className={({ isActive }) =>
                cx(
                  'group flex items-center gap-2.5 rounded-md px-2.5 h-8 text-[13px] transition-colors',
                  isActive ? 'bg-panel-2 text-text' : 'text-dim hover:bg-panel hover:text-text',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <Icon size={16} aria-hidden className={isActive ? 'text-accent' : 'text-muted'} />
                  <span className="flex-1 truncate">{text}</span>
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}

function RunnerStatus() {
  const system = useQuery({ queryKey: ['system'], queryFn: api.system, refetchInterval: 5000 })
  const pending = system.data?.pendingRuns ?? 0
  return (
    <div className="border-t border-border p-3 text-[12px] text-muted flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <Activity size={13} className={pending > 0 ? 'text-info' : 'text-muted'} aria-hidden />
        <span aria-live="polite">{system.isError ? 'API unreachable' : pending > 0 ? `${pending} run(s) queued or running` : 'runner idle'}</span>
      </div>
      <div className="truncate" title={String(system.data?.host.cpuModel ?? '')}>{String(system.data?.host.cpuModel ?? '')}</div>
    </div>
  )
}

/** The sidebar as a drawer below the md breakpoint, where the sidebar itself is hidden. */
function MobileNav({ onClose }: { onClose: () => void }) {
  const close = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    close.current?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="no-print fixed inset-0 z-40 md:hidden">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} aria-hidden />
      <div id="mobile-nav" role="dialog" aria-modal="true" aria-label="Navigation" className="absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col border-r border-border bg-bg-elev shadow-2xl">
        <div className="flex items-center justify-between gap-2 px-4 h-14 border-b border-border">
          <Brand />
          <button ref={close} onClick={onClose} className="flex size-8 items-center justify-center rounded-md text-dim hover:bg-panel-2 hover:text-text" aria-label="Close navigation">
            <X size={18} />
          </button>
        </div>
        <NavList label="Primary (menu)" />
        <RunnerStatus />
      </div>
    </div>
  )
}

function ShortcutHelp({ onClose }: { onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby="shortcut-help-title" className="max-h-full w-full max-w-md overflow-y-auto rounded-lg border border-border bg-panel p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="mb-3 flex items-center justify-between">
          <h2 id="shortcut-help-title" className="font-semibold">Keyboard shortcuts</h2>
          <button autoFocus onClick={onClose} className="flex size-7 items-center justify-center rounded-md text-dim hover:bg-panel-2 hover:text-text" aria-label="Close">
            <X size={16} />
          </button>
        </div>
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
