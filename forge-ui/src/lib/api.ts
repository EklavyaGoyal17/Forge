// Types mirror the server's JSON exactly. Numbers that may be NaN arrive as the string "NaN".

export type Lab = 'PERFORMANCE' | 'JVM' | 'CONCURRENCY' | 'DATABASE' | 'FAILURE' | 'ALGORITHMS'
export type Direction = 'LOWER_IS_BETTER' | 'HIGHER_IS_BETTER' | 'NEUTRAL'
export type Num = number | 'NaN'

export interface ParameterSpec {
  name: string
  label: string
  type: 'INTEGER' | 'ENUM' | 'BOOLEAN'
  defaultValue: number | string | boolean
  min: number | null
  max: number | null
  options: string[]
  unit: string | null
  description: string
  appliesToModes: string[]
}

export interface VariantSpec {
  id: string
  label: string
  description: string
  expectedCorrect: boolean | null
  sourceRef: string
  /** Modes this variant supports; empty = all. */
  modes: string[]
  /** Extra runner JVM flags for this variant (JVM Lab JIT modes); empty for almost every scenario. */
  jvmFlags: string[]
}

export interface MetricSpec {
  name: string
  label: string
  unit: string
  direction: Direction
  description: string
}

export interface ScenarioDescriptor {
  id: string
  version: number
  lab: Lab
  title: string
  summary: string
  question: string
  invariant: string | null
  parameters: ParameterSpec[]
  variants: VariantSpec[]
  metrics: MetricSpec[]
  primaryMetric: string
  trials: { defaultTrials: number; maxTrials: number; defaultWarmup: number; maxWarmup: number }
  defaultTimeoutSeconds: number
  explanation: string
  caveats: string[]
}

export interface ScenarioView {
  descriptor: ScenarioDescriptor
  sources: Record<string, string>
}

export type ExperimentStatus = 'OPEN' | 'CONCLUDED' | 'ARCHIVED'

export interface Experiment {
  id: string
  title: string
  question: string
  hypothesis: string
  hypothesisUpdatedAt: string
  scenarioId: string
  status: ExperimentStatus
  observations: string
  alternativeExplanations: string
  decision: string
  followUps: string
  reviewAnswers: Record<string, string>
  tags: string[]
  createdAt: string
  updatedAt: string
  /** run every other succeeded run is checked against for regressions */
  pinnedBaselineRunId: string | null
}

export interface ExperimentSummary {
  experiment: Experiment
  runCount: number
  succeededRuns: number
  lastRunAt: string | null
}

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'
export type RunRole = 'BASELINE' | 'CANDIDATE' | 'EXPLORATORY'

export interface Trial {
  index: number
  metrics: Record<string, Num>
  invariantHeld: boolean | null
  note: string | null
}

export interface ExplorerStep {
  index: number
  thread: string
  kind: string
  target: string
  detail: string
  outcome: string | null
}

export interface ExplorerTrace {
  outcome: string
  executionOutcome: string
  waitFor: string[]
  totalSteps: number
  truncated: boolean
  threads: string[]
  steps: ExplorerStep[]
}

export interface FailureSeriesPoint {
  tMs: number
  offered: number
  completed: number
  errors: number
  p50Ms?: number
  p99Ms?: number
  maxMs?: number
  attempts: number
  backlog: number
  /** 0 closed, 1 half-open, 2 open (worst seen in the window) */
  breaker: number
  successPercent: number | null
}

export interface FailureTrial {
  trial: number
  series: FailureSeriesPoint[]
  offered: number
  ok: number
  httpErrors: number
  failures: number
  timeouts: number
  unfinished: number
  attempts: number
  retries: number
  shortCircuited: number
  budgetDenied: number
  receivedMinusSent: number
  downstream: { received: number; served: number; errors: number; dropped: number; wasted: number; restarts: number }
  breakerTransitions: { tMs: number; state: 'OPEN' | 'HALF_OPEN' | 'CLOSED' }[]
  recoveryMs: number | null
}

export interface FailureDetails {
  policy: { attemptTimeoutMs: number; maxRetries: number; backoffBaseMs: number; retryBudget: number; breaker: boolean }
  config: {
    fault: string
    faultStartMs: number
    faultEndMs: number
    measureStartMs: number
    measureEndMs: number
    windowMs: number
    rate: number
    downstreamWorkers: number
    downstreamLatencyMs: number
    healthyCapacityRps: number | null
    requestTimeoutMs: number
    bindAddress: string
  }
  trials: FailureTrial[]
}

export interface LivenessWitness {
  prefix: ExplorerStep[]
  cycle: ExplorerStep[]
  prefixLength: number
  cycleLength: number
}

/** Explicit-state liveness exploration (LivenessExplorer): state graph, safety, livelock and starvation cycles. */
export interface LivenessDetails {
  threads: string[]
  states: number
  edges: number
  executions: number
  complete: boolean
  maxStates: number
  safetyViolations: number
  firstSafetyViolation: string | null
  safetyTrace: ExplorerStep[] | null
  deadlocks: number
  deadlockTrace: ExplorerStep[] | null
  livelock: LivenessWitness | null
  starvation: Record<string, LivenessWitness>
  error: string | null
  elapsedMs: number
  fairness: string
  granularity: string
}

export interface ExplorerDetails {
  schedulesExplored: number
  exhaustive: boolean
  maxSchedules: number
  violations: number
  deadlocks: number
  stepLimitHits: number
  errors: number
  firstError: string | null
  outcomes: Record<string, number>
  expected: number
  elapsedMs: number
  counterexample: ExplorerTrace | null
  passingExample: ExplorerTrace | null
  granularity: string
  strategy?: string
}

export interface JmhDetails {
  benchmark: string
  mode: string
  score: Num
  scoreError: Num
  scoreUnit: string
  confidence99_9: Num[]
  forks: { fork: number; scores: number[] }[]
  jmhVersion: string
}

/** HdrHistogram summary in milliseconds; only `count` when the histogram is empty. */
export interface LatencySummary {
  count: number
  meanMs?: number
  minMs?: number
  p50Ms?: number
  p90Ms?: number
  p95Ms?: number
  p99Ms?: number
  p999Ms?: number
  maxMs?: number
}

export interface SpectrumPoint {
  percentile: number
  valueMs: number
  /** false when the sample has fewer than 1/(1-p) values, i.e. the percentile is effectively the maximum */
  resolvable: boolean
}

export interface LoadSeriesPoint {
  tMs: number
  offered: number
  completed: number
  errors: number
  p50Ms?: number
  p99Ms?: number
  maxMs?: number
}

export interface HttpLoadTrial {
  trial: number
  scheduled: number
  offered: number
  sent: number
  ok: number
  httpErrors: number
  failures: number
  timeouts: number
  unfinished: number
  achievedRps: number
  serverErrors: number
  serverMeasuredRequests: number
  occupancyMs: number | null
  littleBoundRps: number | null
  client: LatencySummary
  uncorrected: LatencySummary
  service: LatencySummary
  queueWait: LatencySummary
  timerLag: LatencySummary
  measureStartMs: number
  measureEndMs: number
  series: LoadSeriesPoint[]
  /** wall-clock samples of the pool's worker threads during the sustained phase (profiled platform-thread pools only) */
  workerThreads?: WorkerThreadProfile
}

export interface WorkerThreadProfile {
  samples: number
  threadSamples: number
  periodMs: number
  /** inside a request but not running (sleeping, parked, blocked) */
  blockedShare: number
  /** idle in the pool, waiting for a request */
  idleShare: number
  /** state → share of worker samples */
  states: Record<string, number>
  topFrames: { frame: string; share: number }[]
}

export type LatencyKind = 'client' | 'uncorrected' | 'service' | 'queueWait'

export interface HttpLoadDetails {
  design: string
  bindAddress: string
  /** 0 = unbounded (virtual threads) */
  workers: number
  config: {
    rate: number
    warmupMs: number
    durationMs: number
    poolSize: number
    downstreamMs: number
    cpuWorkUs: number
    cpuIterations: number
    iterationsPerMicro: number
    connections: number
    requestTimeoutMs: number
    windowMs: number
  }
  trials: HttpLoadTrial[]
  aggregate: Record<LatencyKind, LatencySummary> & { spectrum: Record<LatencyKind, SpectrumPoint[]> }
  histogram: { unit: string; significantDigits: number; highestTrackableMs: number }
}

/** One node of PostgreSQL's EXPLAIN (FORMAT JSON) output, keys exactly as PostgreSQL emits them. */
export interface PlanNode {
  'Node Type': string
  'Relation Name'?: string
  'Index Name'?: string
  'Actual Total Time'?: number
  'Actual Rows'?: number
  'Actual Loops'?: number
  'Plan Rows'?: number
  'Shared Hit Blocks'?: number
  'Shared Read Blocks'?: number
  'Rows Removed by Filter'?: number
  Plans?: PlanNode[]
  [key: string]: unknown
}

export interface ExplainCapture {
  label: string
  executionMs: number
  planningMs: number
  sharedHitBlocks: number
  sharedReadBlocks: number
  rootRows: number
  scannedRows: number
  nodes: string[]
  truncated: boolean
  plan: PlanNode
}

export interface DatabaseDetails {
  postgres?: Record<string, string>
  dataset?: { accounts: number; txnRows: number; seedMs: number; accountTableMb: number; txnTableMb: number }
  index?: { name: string; ddl: string; buildMs?: number; sizeMb?: number } | null
  queries?: { label: string; sql: string }[]
  statements?: { label: string; sql: string }[]
  plans?: ExplainCapture[]
  probe?: { accountId: number; rows: number; checksum: number; sql: string }
  sweep?: { page: number; medianMs: number; rowsScanned: number; explainExecutionMs: number }[]
}

// ---- JVM Lab: summaries of a JFR recording made inside the runner JVM ----

export interface JfrFrameRow { frame: string; samples: number; share: number; frameTypes: Record<string, number> | null }
export interface JfrPathRow { frames: string[]; samples: number; share: number }
export interface JfrAllocationClassRow { objectClass: string; weightBytes: number; share: number; samples: number }
export interface JfrAllocationSiteRow { objectClass: string; topFrame: string; appFrame: string | null; weightBytes: number; share: number; samples: number }
export interface JfrGcPause { gcId: number; name: string; cause: string; atMs: number; sumOfPausesMs: number; longestPauseMs: number; heapBeforeBytes: number | null; heapAfterBytes: number | null }
export interface JfrContentionRow { lockClass: string; appFrame: string | null; events: number; totalMs: number; maxMs: number; stackTop: string[] }
export interface JfrContention { thresholdMs: number; events: number; totalMs: number; rows: JfrContentionRow[] }
export interface JfrCompilation { method: string; classLoader: string; level: number; osr: boolean; atMs: number; durationMs: number; endAtMs: number; codeSize: number; succeeded: boolean }
export interface JfrDeoptimization { method: string; classLoader: string; reason: string; action: string; atMs: number; lineNumber: number }
export interface JfrMarker { label: string; atMs: number; nanoTime: number }

export interface JfrSummary {
  recording: {
    durationMs: number
    fileBytes: number
    eventCounts: Record<string, number>
    settings: Record<string, string>
    windowStartMs: number | null
    windowEndMs: number | null
    truncatedTables: string[]
  }
  cpu: { samples: number; periodMs: number; topSelf: JfrFrameRow[]; topTotal: JfrFrameRow[]; hotPaths: JfrPathRow[] }
  allocation: { samples: number; sampledBytes: number; discardedFirstSamples: number; byClass: JfrAllocationClassRow[]; sites: JfrAllocationSiteRow[] }
  gc: { youngCollector: string | null; oldCollector: string | null; collections: number; totalPauseMs: number; maxPauseMs: number; pauses: JfrGcPause[] }
  locks: { monitorEnter: JfrContention; threadPark: JfrContention }
  compilations: JfrCompilation[]
  deoptimizations: JfrDeoptimization[]
  markers: JfrMarker[]
}

/** A compilation of the warm-up kernel, placed on the invocation axis (-1: after the last invocation). */
export interface WarmupCompilation { method: string; level: number; osr: boolean; succeeded: boolean; atInvocation: number; compileMs: number; codeSize: number }

export interface WarmupTrialSeries { trial: number; invocationNanos: number[]; compilations: WarmupCompilation[]; deoptimizations: JfrDeoptimization[] }

export interface JvmDetails {
  kind: 'warmup' | 'allocation' | 'contention' | 'profile'
  jitMode: { variantFlags: string[]; compiler: string | null; jvmArguments: string[] }
  warmup?: { invocations: number; workSize: number; trials: WarmupTrialSeries[] }
  allocation?: { mode: string; size: number; opsPerTrial: number; exactAllocatedBytes: number; reference: Record<string, unknown> }
  contention?: { threads: number; incrementsPerThread: number; workRounds: number; availableProcessors: number; contentionMonitoring: boolean }
  jfr: JfrSummary
}

export interface ScenarioResult {
  trials: Trial[]
  invariant: { description: string | null; checked: number; violations: number; held: boolean | null }
  observations: string[]
  details: { explorer?: ExplorerDetails; liveness?: LivenessDetails; failure?: FailureDetails; jmh?: JmhDetails; httpLoad?: HttpLoadDetails; database?: DatabaseDetails; jvm?: JvmDetails; [k: string]: unknown }
}

export interface Run {
  id: string
  experimentId: string
  seq: number
  label: string
  role: RunRole
  scenarioId: string
  scenarioVersion: number
  variant: string
  params: Record<string, number | string | boolean>
  seed: number
  trials: number
  warmupTrials: number
  heapMb: number
  timeoutSeconds: number
  status: RunStatus
  statusReason: string | null
  exitCode: number | null
  progressDone: number | null
  progressTotal: number | null
  progressPhase: string | null
  environment: Environment | null
  result: ScenarioResult | null
  forgeVersion: string
  cancelRequested: boolean
  queuedAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface Environment {
  runner?: Record<string, unknown> | null
  host?: Record<string, unknown>
  launch?: Record<string, unknown>
}

export interface Summary {
  n: number
  mean: Num
  stddev: Num
  min: Num
  max: Num
  median: Num
  p90: Num
  p95: Num
  p99: Num
  cv: Num
}

export interface ArtifactMeta {
  name: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

export interface RunView {
  run: Run
  summaries: Record<string, Summary>
  artifacts: ArtifactMeta[]
}

export interface RunEvent {
  id: number
  ts: string
  level: string
  message: string
}

export type Verdict = 'IMPROVEMENT' | 'REGRESSION' | 'NO_DIFFERENCE_DETECTED' | 'NOT_DIRECTIONAL' | 'IDENTICAL'

export interface ComparisonResult {
  metric: string
  direction: Direction
  baseline: Summary
  candidate: Summary
  ratioOfMedians: Num
  ciLow: Num
  ciHigh: Num
  relativeChangePercent: Num
  mannWhitney: { u: number; z: number; pValue: number; probabilityAGreater: number } | null
  verdict: Verdict
  interpretation: string
  warnings: string[]
}

export type RegressionGate = 'REGRESSION' | 'IMPROVEMENT' | 'NO_DIFFERENCE_DETECTED' | 'IDENTICAL' | 'NOT_DIRECTIONAL' | 'NOT_COMPARABLE'

export interface RegressionCheck {
  runId: string
  seq: number
  variant: string
  gate: RegressionGate
  reasons: string[]
  primary: ComparisonResult | null
  otherRegressedMetrics: string[]
}

export interface RegressionReport {
  baselineRunId: string | null
  baselineSeq: number | null
  baselineVariant: string | null
  primaryMetric: string | null
  checks: RegressionCheck[]
}

export interface Difference {
  field: string
  label: string
  baseline: string | null
  candidate: string | null
}

export interface ComparisonView {
  baselineRunId: string
  candidateRunId: string
  scenarioId: string
  baselineVariant: string
  candidateVariant: string
  primaryMetric: string | null
  metrics: { label: string; unit: string; primary: boolean; result: ComparisonResult }[]
  parameterDifferences: Difference[]
  environmentDifferences: Difference[]
  comparabilityWarnings: string[]
  baselineInvariant: string
  candidateInvariant: string
  baselineInvariantHeld: boolean | null
  candidateInvariantHeld: boolean | null
}

export type CheckStatus = 'PASS' | 'WARN' | 'FAIL' | 'NOT_APPLICABLE'

export interface Review {
  checks: { id: string; criterion: string; status: CheckStatus; evidence: string }[]
  passed: number
  applicable: number
  questions: { id: string; text: string; hint: string }[]
  baselineRunId: string | null
  candidateRunId: string | null
  rubricNote: string
}

export interface SystemView {
  host: Record<string, unknown>
  limits: {
    maxConcurrentRuns: number
    queueCapacity: number
    defaultHeapMb: number
    maxHeapMb: number
    maxTimeoutSeconds: number
    activeProcessorCount: number
    stdoutCapBytes: number
  }
  pendingRuns: number
  queuedRuns: number
  scenarios: number
  isolation: string
}

export interface FieldError {
  field: string
  message: string
}

export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: FieldError[]

  constructor(status: number, message: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let detail = res.statusText
    let fieldErrors: FieldError[] = []
    try {
      const pd = await res.json()
      detail = pd.detail ?? detail
      fieldErrors = pd.fieldErrors ?? []
    } catch {
      // non-JSON error body
    }
    throw new ApiError(res.status, detail, fieldErrors)
  }
  if (res.status === 204) return undefined as T
  const type = res.headers.get('Content-Type') ?? ''
  return (type.includes('json') ? res.json() : res.text()) as Promise<T>
}

export interface CreateRunRequest {
  variant: string
  params: Record<string, number | string | boolean>
  seed?: number
  trials: number
  /** omitted: the scenario's default */
  warmupTrials?: number
  role: RunRole
  label?: string
  heapMb?: number
  timeoutSeconds?: number
}

// ---- AI Investigator ----

export interface InvestigatorStatus { provider: 'none' | 'anthropic' | 'local'; model: string | null; enabled: boolean; sendsEvidenceOffHost: boolean; description: string }
export interface EvidenceItem { id: string; kind: string; title: string; runSeq: number | null; text: string }
export type FindingKind = 'OBSERVATION' | 'HYPOTHESIS' | 'CAVEAT' | 'NEXT_EXPERIMENT'
export interface Finding { kind: FindingKind; rule: string; text: string; citations: string[] }
export interface InvestigationAnalysis { experimentId: string; provider: InvestigatorStatus; evidence: EvidenceItem[]; findings: Finding[] }
export interface Investigation {
  id: string
  experimentId: string
  question: string
  provider: string
  model: string | null
  answer: string
  citations: string[]
  check: { unknownCitations: string[]; unverifiedNumbers: string[]; checkedNumbers: number; uncited?: boolean; stopReason?: string | null; note?: string; inputTokens?: number | null; outputTokens?: number | null }
  evidenceIds: string[]
  createdAt: string
}

// ---- Challenge Arena ----

export interface ChallengeInfo {
  id: string
  number: number
  title: string
  scenarioId: string
  lab: Lab | null
  problem: string
  goal: string
  question: string
  start: { variant: string; params: Record<string, string | number | boolean>; trials: number }
  hintCount: number
  reviewQuestions: string[]
  criteria: string[]
}
export interface CriterionResult { id: string; description: string; status: 'PASS' | 'NOT_YET'; evidence: string; runs: number[] }
export interface ChallengeAttempt { id: string; challengeId: string; experimentId: string; hintsRevealed: number; answers: Record<string, string>; startedAt: string; completedAt: string | null }
export interface AttemptView {
  challenge: ChallengeInfo
  attempt: ChallengeAttempt
  experimentTitle: string
  successfulRuns: number
  criteria: CriterionResult[]
  passed: number
  complete: boolean
  hints: string[]
}
export interface ChallengeSummary { challenge: ChallengeInfo; latest: AttemptView | null; attempts: number; everCompleted: boolean }

export const api = {
  system: () => request<SystemView>('GET', '/system'),
  scenarios: () => request<ScenarioDescriptor[]>('GET', '/scenarios'),
  scenario: (id: string) => request<ScenarioView>('GET', `/scenarios/${encodeURIComponent(id)}`),
  experiments: (q?: string, lab?: string) => {
    const p = new URLSearchParams()
    if (q) p.set('q', q)
    if (lab) p.set('lab', lab)
    const qs = p.toString()
    return request<ExperimentSummary[]>('GET', `/experiments${qs ? `?${qs}` : ''}`)
  },
  experiment: (id: string) => request<{ experiment: Experiment; runs: Run[] }>('GET', `/experiments/${id}`),
  createExperiment: (body: { title: string; question: string; hypothesis: string; scenarioId: string; tags: string[] }) =>
    request<Experiment>('POST', '/experiments', body),
  updateExperiment: (id: string, fields: Partial<Experiment>) => request<Experiment>('PATCH', `/experiments/${id}`, fields),
  deleteExperiment: (id: string) => request<void>('DELETE', `/experiments/${id}`),
  createRun: (experimentId: string, body: CreateRunRequest) => request<Run>('POST', `/experiments/${experimentId}/runs`, body),
  run: (id: string) => request<RunView>('GET', `/runs/${id}`),
  recentRuns: (limit = 15) => request<Run[]>('GET', `/runs?limit=${limit}`),
  events: (id: string) => request<RunEvent[]>('GET', `/runs/${id}/events`),
  cancelRun: (id: string) => request<Run>('POST', `/runs/${id}/cancel`),
  artifact: (id: string, name: string) => request<string>('GET', `/runs/${id}/artifacts/${encodeURIComponent(name)}`),
  compare: (baseline: string, candidate: string) =>
    request<ComparisonView>('GET', `/compare?baseline=${baseline}&candidate=${candidate}`),
  review: (id: string) => request<Review>('GET', `/experiments/${id}/review`),
  pinBaseline: (id: string, runId: string) => request<Experiment>('PUT', `/experiments/${id}/pinned-baseline`, { runId }),
  unpinBaseline: (id: string) => request<Experiment>('DELETE', `/experiments/${id}/pinned-baseline`),
  regressions: (id: string) => request<RegressionReport>('GET', `/experiments/${id}/regressions`),
  report: (id: string) => request<string>('GET', `/experiments/${id}/report.md`),
  investigatorStatus: () => request<InvestigatorStatus>('GET', '/investigator/status'),
  investigation: (id: string) => request<InvestigationAnalysis>('GET', `/experiments/${id}/investigation`),
  investigations: (id: string) => request<Investigation[]>('GET', `/experiments/${id}/investigation/questions`),
  ask: (id: string, question: string) => request<Investigation>('POST', `/experiments/${id}/investigation/questions`, { question }),
  challenges: () => request<ChallengeSummary[]>('GET', '/challenges'),
  challenge: (id: string) => request<{ challenge: ChallengeInfo; attempts: AttemptView[] }>('GET', `/challenges/${encodeURIComponent(id)}`),
  startChallenge: (id: string, hypothesis: string) => request<AttemptView>('POST', `/challenges/${encodeURIComponent(id)}/attempts`, { hypothesis }),
  attempt: (id: string) => request<AttemptView>('GET', `/challenge-attempts/${id}`),
  revealHint: (id: string) => request<AttemptView>('POST', `/challenge-attempts/${id}/hints`),
  saveAnswers: (id: string, answers: Record<string, string>) => request<AttemptView>('PUT', `/challenge-attempts/${id}/answers`, answers),
}
