import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider, createBrowserRouter } from 'react-router'
import { Layout } from './components/Layout'
import './index.css'
import { MODULES } from './lib/labs'
import { Dashboard } from './pages/Dashboard'
import { ExperimentPage } from './pages/ExperimentPage'
import { ExperimentsPage, NewExperimentPage } from './pages/Experiments'
import { InvestigatorPage } from './pages/Investigator'
import { LabPage } from './pages/LabPage'
import { NotebookPage } from './pages/Notebook'
import { PlannedModule } from './pages/PlannedModule'

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } },
})

const module = (path: string) => MODULES.find((m) => m.path === path)!

const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'experiments', element: <ExperimentsPage /> },
      { path: 'experiments/new', element: <NewExperimentPage /> },
      { path: 'experiments/:id', element: <ExperimentPage /> },
      { path: 'labs/performance', element: <LabPage lab="PERFORMANCE" /> },
      { path: 'labs/concurrency', element: <LabPage lab="CONCURRENCY" /> },
      { path: 'labs/algorithms', element: <LabPage lab="ALGORITHMS" /> },
      { path: 'labs/jvm', element: <LabPage lab="JVM" /> },
      { path: 'labs/database', element: <LabPage lab="DATABASE" /> },
      { path: 'labs/failure', element: <LabPage lab="FAILURE" /> },
      { path: 'challenges', element: <PlannedModule module={module('/challenges')} /> },
      { path: 'investigator', element: <InvestigatorPage /> },
      { path: 'notebook', element: <NotebookPage /> },
      { path: '*', element: <div className="p-6 text-dim">Page not found.</div> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
