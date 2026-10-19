import { render, screen, within } from '@testing-library/react'
import { beforeAll, describe, expect, it } from 'vitest'
import { DatabaseView, PlanTree, planRows } from '../components/DatabaseView'
import type { DatabaseDetails, ExplainCapture, PlanNode } from '../lib/api'

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

// Shape of a real PostgreSQL 17 plan for the account-index variant (values shortened).
const plan: PlanNode = {
  'Node Type': 'Sort',
  'Actual Total Time': 0.9,
  'Actual Rows': 12,
  'Actual Loops': 1,
  'Plan Rows': 11,
  'Sort Key': ['created_at'],
  'Shared Hit Blocks': 15,
  'Shared Read Blocks': 0,
  Plans: [
    {
      'Node Type': 'Bitmap Heap Scan',
      'Relation Name': 'txn',
      'Actual Total Time': 0.7,
      'Actual Rows': 12,
      'Actual Loops': 1,
      'Plan Rows': 11,
      'Rows Removed by Filter': 48,
      Filter: '(created_at >= ...)',
      'Shared Hit Blocks': 15,
      Plans: [
        { 'Node Type': 'Bitmap Index Scan', 'Index Name': 'txn_account_idx', 'Actual Total Time': 0.05, 'Actual Rows': 60, 'Actual Loops': 1, 'Plan Rows': 2, 'Index Cond': '(account_id = 42)' },
      ],
    },
  ],
}

const capture: ExplainCapture = {
  label: 'probe lookup, trial 1',
  executionMs: 0.95,
  planningMs: 0.2,
  sharedHitBlocks: 15,
  sharedReadBlocks: 0,
  rootRows: 12,
  scannedRows: 120,
  nodes: ['Sort', '  Bitmap Heap Scan on txn', '    Bitmap Index Scan using txn_account_idx'],
  truncated: false,
  plan,
}

describe('planRows', () => {
  it('flattens depth-first with exclusive time and estimate error', () => {
    const rows = planRows(plan)
    expect(rows.map((r) => [r.depth, r.title])).toEqual([
      [0, 'Sort'],
      [1, 'Bitmap Heap Scan on txn'],
      [2, 'Bitmap Index Scan using txn_account_idx'],
    ])
    expect(rows[0].exclusiveMs).toBeCloseTo(0.2)
    expect(rows[1].exclusiveMs).toBeCloseTo(0.65)
    expect(rows[2].misestimate).toBe(30) // 60 actual vs 2 estimated
    expect(rows[0].misestimate).toBeCloseTo(12 / 11)
  })

  it('multiplies by loops', () => {
    const looped: PlanNode = { 'Node Type': 'Index Scan', 'Actual Total Time': 0.01, 'Actual Rows': 3, 'Actual Loops': 100, 'Plan Rows': 3 }
    const [r] = planRows(looped)
    expect(r.rows).toBe(300)
    expect(r.exclusiveMs).toBeCloseTo(1)
  })
})

describe('PlanTree', () => {
  it('renders every node with rows vs estimate and flags large misestimates', () => {
    render(<PlanTree capture={capture} />)
    const items = within(screen.getByRole('list', { name: 'Plan: probe lookup, trial 1' })).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[2]).toHaveTextContent('Bitmap Index Scan using txn_account_idx')
    expect(within(items[2]).getByText('estimate off ×30')).toBeInTheDocument()
    expect(within(items[1]).queryByText(/estimate off/)).toBeNull()
    expect(items[1]).toHaveTextContent('removed by filter 48')
    expect(items[0]).toHaveTextContent('Sort Keycreated_at')
  })
})

describe('DatabaseView', () => {
  const details: DatabaseDetails = {
    postgres: { server_version: '17.6', shared_buffers: '128MB', work_mem: '4MB', random_page_cost: '4', max_parallel_workers_per_gather: '2', jit: 'on' },
    dataset: { accounts: 10000, txnRows: 500000, seedMs: 3210.4, accountTableMb: 0.8, txnTableMb: 41.2 },
    index: { name: 'txn_account_idx', ddl: 'create index txn_account_idx on txn (account_id)', buildMs: 512.3, sizeMb: 10.7 },
    queries: [{ label: 'lookup', sql: 'select created_at, amount\nfrom txn\nwhere account_id = ?' }],
    plans: [capture, { ...capture, label: 'probe lookup, trial 5' }],
    sweep: [
      { page: 1, medianMs: 0.4, rowsScanned: 50, explainExecutionMs: 0.05 },
      { page: 1000, medianMs: 9.8, rowsScanned: 50000, explainExecutionMs: 8.9 },
    ],
  }

  it('shows the environment, the exact SQL, a plan selector and the sweep values', () => {
    render(<DatabaseView details={details} />)
    expect(screen.getByText('17.6')).toBeInTheDocument()
    expect(screen.getByText('SQL · lookup (exactly as executed)')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Captured plan' })).toHaveTextContent('probe lookup, trial 5')
    const sweep = screen.getByRole('table', { name: 'Page depth sweep values' })
    expect(within(sweep).getAllByRole('row')).toHaveLength(3)
    expect(within(sweep).getByText('50,000')).toBeInTheDocument()
    expect(screen.getByRole('figure', { name: /page time and rows read/i })).toBeInTheDocument()
  })

  it('says when there is no secondary index', () => {
    render(<DatabaseView details={{ ...details, index: null, sweep: undefined }} />)
    expect(screen.getByText('No secondary index (only primary keys).')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Page depth sweep values' })).toBeNull()
  })
})
