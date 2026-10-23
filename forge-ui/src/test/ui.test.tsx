import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Tabs } from '../components/ui'

function Harness() {
  const [tab, setTab] = useState<'a' | 'b' | 'c'>('a')
  return (
    <Tabs label="Sections" value={tab} onChange={setTab} tabs={[{ id: 'a', label: 'Alpha' }, { id: 'b', label: 'Beta' }, { id: 'c', label: 'Gamma' }]}>
      <p>content of {tab}</p>
    </Tabs>
  )
}

describe('Tabs', () => {
  it('is one tab stop and labels its panel with the selected tab', () => {
    render(<Harness />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.tabIndex)).toEqual([0, -1, -1])
    const panel = screen.getByRole('tabpanel', { name: 'Alpha' })
    expect(panel).toHaveTextContent('content of a')
    expect(tabs[0]).toHaveAttribute('aria-controls', panel.id)
  })

  it('moves and selects with the arrow keys, Home and End, wrapping at the ends', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.tab()
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveFocus()
    expect(screen.getByRole('tabpanel', { name: 'Beta' })).toHaveTextContent('content of b')
    await user.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Gamma' })).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveFocus()
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: 'Gamma' })).toHaveFocus()
    await user.keyboard('{Home}')
    expect(screen.getByRole('tabpanel', { name: 'Alpha' })).toBeInTheDocument()
  })
})
