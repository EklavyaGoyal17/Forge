import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Layout } from '../components/Layout'
import { api } from '../lib/api'
import { MODULES } from '../lib/labs'

function shell() {
  vi.spyOn(api, 'system').mockResolvedValue(undefined as never)
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<Layout />}>
            <Route index element={<p>dashboard</p>} />
            <Route path="labs/performance" element={<p>performance page</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('application shell', () => {
  it('gives every module an icon, so no sidebar row is misaligned', () => {
    shell()
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((l) => l.textContent)).toEqual(MODULES.map((m) => m.label))
    for (const link of links) expect(link.querySelector('svg')).not.toBeNull()
  })

  it('opens the navigation drawer from the menu button and closes it with Escape, returning focus', async () => {
    const user = userEvent.setup()
    shell()
    const menu = screen.getByRole('button', { name: 'Open navigation' })
    expect(menu).toHaveAttribute('aria-expanded', 'false')
    await user.click(menu)
    const drawer = screen.getByRole('dialog', { name: 'Navigation' })
    expect(menu).toHaveAttribute('aria-expanded', 'true')
    expect(within(drawer).getAllByRole('link')).toHaveLength(MODULES.length)
    expect(within(drawer).getByRole('button', { name: 'Close navigation' })).toHaveFocus()
    const found = await axe.run(drawer, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] }, rules: { 'color-contrast': { enabled: false } } })
    expect(found.violations.map((v) => v.id)).toEqual([])
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Navigation' })).toBeNull()
    expect(menu).toHaveFocus()
  })

  it('closes the drawer once a page is chosen', async () => {
    const user = userEvent.setup()
    shell()
    await user.click(screen.getByRole('button', { name: 'Open navigation' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Navigation' })).getByRole('link', { name: 'Performance Lab' }))
    expect(screen.getByText('performance page')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Navigation' })).toBeNull()
  })
})
