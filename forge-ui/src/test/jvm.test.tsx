import { render, screen, within } from '@testing-library/react'
import { beforeAll, describe, expect, it } from 'vitest'
import { JvmView, markerWindows, rollingMedian, shortFrame, tierLabel } from '../components/JvmView'
import type { JfrSummary, JvmDetails } from '../lib/api'

beforeAll(() => {
  // jsdom has no ResizeObserver; Recharts' ResponsiveContainer needs one to mount.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

const PKG = 'dev.forge.lab.scenarios.jvm.'

// Shapes and magnitudes taken from real runner output (values shortened).
const jfr = (over: Partial<JfrSummary> = {}): JfrSummary => ({
  recording: {
    durationMs: 646.2,
    fileBytes: 154_916,
    eventCounts: { 'jdk.ExecutionSample': 52, 'jdk.GarbageCollection': 2, 'forge.Marker': 4 },
    settings: { 'jdk.ExecutionSample#period': '10 ms', 'jdk.JavaMonitorEnter#threshold': '100 us' },
    windowStartMs: 12.5,
    windowEndMs: 600.1,
    truncatedTables: [],
  },
  cpu: {
    samples: 32,
    periodMs: 10,
    topSelf: [{ frame: `${PKG}WarmupKernel.work`, samples: 28, share: 0.875, frameTypes: { 'JIT compiled': 25, Interpreted: 3 } }],
    topTotal: [{ frame: `${PKG}WarmupKernel.measure`, samples: 30, share: 0.9375, frameTypes: null }],
    hotPaths: [{ frames: [`${PKG}WarmupKernel.work`, `${PKG}WarmupKernel.measure`], samples: 28, share: 0.875 }],
  },
  allocation: {
    samples: 238,
    sampledBytes: 1_943_000_000,
    discardedFirstSamples: 2,
    byClass: [
      { objectClass: 'java.lang.Long', weightBytes: 1_309_000_000, share: 0.674, samples: 150 },
      { objectClass: 'java.lang.Integer', weightBytes: 634_000_000, share: 0.326, samples: 88 },
    ],
    sites: [
      { objectClass: 'java.lang.Long', topFrame: 'java.lang.Long.valueOf:1000', appFrame: `${PKG}AllocationScenario$$Lambda.apply`, weightBytes: 707_000_000, share: 0.364, samples: 80 },
      { objectClass: 'byte[]', topFrame: 'java.util.Arrays.copyOf:3537', appFrame: null, weightBytes: 1_000, share: 0.001, samples: 1 },
    ],
  },
  gc: {
    youngCollector: 'G1New',
    oldCollector: 'G1Old',
    collections: 2,
    totalPauseMs: 6.0,
    maxPauseMs: 3.2,
    pauses: [
      { gcId: 1, name: 'G1New', cause: 'G1 Evacuation Pause', atMs: 81.9, sumOfPausesMs: 2.8, longestPauseMs: 2.8, heapBeforeBytes: 120_508_504, heapAfterBytes: 11_782_352 },
      { gcId: 2, name: 'G1New', cause: 'G1 Evacuation Pause', atMs: 397.3, sumOfPausesMs: 3.2, longestPauseMs: 3.2, heapBeforeBytes: null, heapAfterBytes: null },
    ],
  },
  locks: {
    monitorEnter: {
      thresholdMs: 0.1,
      events: 239,
      totalMs: 30.4,
      rows: [
        { lockClass: `${PKG}LockContentionScenario$SynchronizedAccumulator`, appFrame: `${PKG}LockContentionScenario$SynchronizedAccumulator.add`, events: 238, totalMs: 30.1, maxMs: 1.9, stackTop: [`${PKG}LockContentionScenario$SynchronizedAccumulator.add:70`] },
        { lockClass: 'jdk.jfr.internal.PlatformRecorder', appFrame: null, events: 1, totalMs: 0.3, maxMs: 0.3, stackTop: ['jdk.jfr.internal.PlatformRecorder.periodicTask:495'] },
      ],
    },
    threadPark: { thresholdMs: 0.1, events: 0, totalMs: 0, rows: [] },
  },
  compilations: [],
  deoptimizations: [],
  markers: [
    { label: 'trial-0 start', atMs: 12.5, nanoTime: 1 },
    { label: 'trial-0 end', atMs: 300.2, nanoTime: 2 },
    { label: 'trial-1 start', atMs: 300.4, nanoTime: 3 },
    { label: 'trial-1 end', atMs: 600.1, nanoTime: 4 },
  ],
  ...over,
})

const warmup: JvmDetails = {
  kind: 'warmup',
  jitMode: { variantFlags: [], compiler: 'HotSpot 64-Bit Tiered Compilers', jvmArguments: ['-Xmx256m'] },
  warmup: {
    invocations: 6,
    workSize: 10_000,
    trials: [
      {
        trial: 0,
        invocationNanos: [400_400, 330_000, 90_000, 40_000, 15_500, 15_300],
        compilations: [
          { method: 'work', level: 3, osr: true, succeeded: true, atInvocation: 2, compileMs: 0.24, codeSize: 856 },
          { method: 'work', level: 4, osr: false, succeeded: true, atInvocation: 4, compileMs: 7.03, codeSize: 1568 },
          { method: 'work', level: 4, osr: true, succeeded: true, atInvocation: -1, compileMs: 7.0, codeSize: 2024 },
        ],
        deoptimizations: [],
      },
      { trial: 1, invocationNanos: [280_000, 280_000, 280_000], compilations: [], deoptimizations: [] },
    ],
  },
  jfr: jfr(),
}

describe('helpers', () => {
  it('shortens frames to class and method', () => {
    expect(shortFrame(`${PKG}AllocationScenario$$Lambda.apply`)).toBe('AllocationScenario$$Lambda.apply')
    expect(shortFrame('java.lang.Long.valueOf:1000')).toBe('Long.valueOf:1000')
    expect(shortFrame('java.util.concurrent.locks.ReentrantLock$NonfairSync')).toBe('ReentrantLock$NonfairSync')
    expect(shortFrame('byte[]')).toBe('byte[]')
    expect(shortFrame('java.lang.Integer[]')).toBe('Integer[]')
    expect(shortFrame(null)).toBe('—')
  })

  it('names HotSpot tiers', () => {
    expect(tierLabel(3, true)).toBe('C1 tier 3 OSR')
    expect(tierLabel(1, false)).toBe('C1 tier 1')
    expect(tierLabel(4, false)).toBe('C2')
    expect(tierLabel(4, true)).toBe('C2 OSR')
  })

  it('computes a centred rolling median that ignores single outliers', () => {
    expect(rollingMedian([1, 1, 100, 1, 1], 3)).toEqual([1, 1, 1, 1, 1])
    expect(rollingMedian([5, 4, 3, 2, 1], 5)).toEqual([4, 3.5, 3, 2.5, 2])
  })

  it('pairs start/end markers into trial windows', () => {
    expect(markerWindows(jfr().markers)).toEqual([
      { name: 'trial-0', fromMs: 12.5, toMs: 300.2 },
      { name: 'trial-1', fromMs: 300.4, toMs: 600.1 },
    ])
    expect(markerWindows([{ label: 'x end', atMs: 1, nanoTime: 0 }])).toEqual([])
  })
})

describe('JvmView', () => {
  it('shows each compilation of the warm-up kernel with the invocation its code was available from', () => {
    render(<JvmView details={warmup} />)
    expect(screen.getByText(/HotSpot 64-Bit Tiered Compilers/)).toBeInTheDocument()
    const table = screen.getByRole('table', { name: /JIT compilations of the kernel in trial 1/ })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('C1 tier 3 OSR')).toBeInTheDocument()
    expect(within(rows[0]).getByText('invocation 2')).toBeInTheDocument()
    expect(within(rows[1]).getByText('C2')).toBeInTheDocument()
    expect(within(rows[2]).getByText('after the last invocation')).toBeInTheDocument()
    expect(screen.getByLabelText('Trial')).toBeInTheDocument()
  })

  it('shows hot methods with how the sampled frames were executing', () => {
    render(<JvmView details={warmup} />)
    const self = screen.getByRole('table', { name: /Top of stack \(self\)/ })
    expect(within(self).getByText('WarmupKernel.work')).toHaveAttribute('title', `${PKG}WarmupKernel.work`)
    expect(within(self).getByText('JIT compiled 25 · Interpreted 3')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: /Scenario methods anywhere on the stack/ })).toBeInTheDocument()
  })

  it('renders allocation by class and site next to the exact counter, and the GC pauses', () => {
    const details: JvmDetails = {
      kind: 'allocation',
      jitMode: warmup.jitMode,
      allocation: { mode: 'aggregate', size: 1_000_000, opsPerTrial: 10, exactAllocatedBytes: 1_998_777_560, reference: { kind: 'long', value: 1 } },
      jfr: jfr(),
    }
    render(<JvmView details={details} />)
    expect(screen.getByText(/the measuring thread's exact counter says 1\.9 GB/)).toBeInTheDocument()
    expect(screen.getByText(/2 first sample\(s\) per thread left out/)).toBeInTheDocument()
    const byClass = screen.getByRole('table', { name: 'By class' })
    expect(within(byClass).getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual(['Long', 'Integer'])
    const sites = screen.getByRole('table', { name: 'By allocation site' })
    expect(within(sites).getByText('Long.valueOf:1000')).toBeInTheDocument()
    expect(within(sites).getByText('— (not in a scenario frame)')).toBeInTheDocument()
    expect(screen.getByText(/2 collection\(s\), 6 ms of pauses in total, longest 3\.2 ms/)).toBeInTheDocument()
    expect(screen.getByLabelText('GC pause timeline')).toBeInTheDocument()
    expect(screen.getByText('All 2 listed collections')).toBeInTheDocument()
  })

  it('separates application lock waits from JDK-internal ones and states the threshold', () => {
    render(<JvmView details={{ ...warmup, kind: 'contention', warmup: undefined, contention: { threads: 4, incrementsPerThread: 200_000, workRounds: 20, availableProcessors: 8, contentionMonitoring: true } }} />)
    const monitor = screen.getByRole('table', { name: /jdk\.JavaMonitorEnter.*239 wait\(s\) ≥ 0\.1 ms, 30\.4 ms in total/ })
    const rows = within(monitor).getAllByRole('row').slice(1)
    expect(within(rows[0]).getAllByText('LockContentionScenario$SynchronizedAccumulator.add').length).toBeGreaterThan(0)
    expect(within(rows[1]).getByText('— (JDK / JFR thread)')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: /jdk\.ThreadPark.*0 wait\(s\)/ })).toBeInTheDocument()
  })

  it('says so when the interpreter ran every invocation', () => {
    const interpreted: JvmDetails = {
      ...warmup,
      jitMode: { variantFlags: ['-Xint'], compiler: null, jvmArguments: ['-Xint'] },
      warmup: { ...warmup.warmup!, trials: [warmup.warmup!.trials[1]] },
      jfr: jfr({ gc: { ...jfr().gc, collections: 0, pauses: [], totalPauseMs: 0, maxPauseMs: 0 } }),
    }
    render(<JvmView details={interpreted} />)
    expect(screen.getByText('none (interpreter only)')).toBeInTheDocument()
    expect(screen.getByText('-Xint')).toBeInTheDocument()
    expect(screen.getByText(/every invocation ran in the interpreter/)).toBeInTheDocument()
    expect(screen.getByText('No garbage collection in the measured window.')).toBeInTheDocument()
  })
})
