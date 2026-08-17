// Typed wrappers for the workflow surface (research tasks).
//
// Mirrors `ResearchController`. The shapes below are the JSON
// projection returned by `ResearchTaskResponse` plus the trace
// items returned by `/tasks/{taskId}/progress`.

import type { ApiClient } from './index';

export type WorkflowStatus =
  | 'CREATED'
  | 'WAITING_FOR_LEASE'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'RETRYING';

export type WorkflowTaskType =
  | 'FINANCIAL_DATA_INGESTION'
  | 'FINANCIAL_METRIC_RECALCULATION'
  | 'DOCUMENT_INDEX_BUILD'
  | 'COMPANY_INTELLIGENCE_BUILD'
  | 'STOCK_AI_ANALYSIS';

export interface WorkflowTask {
  id: string;
  symbol: string;
  taskType: WorkflowTaskType;
  status: WorkflowStatus;
  stage: string;
  attempts: number;
  idempotencyKey: string;
  leaseOwner: string | null;
  fencingToken: number | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  elapsedMillis: number;
}

export interface WorkflowProgress {
  task: WorkflowTask;
  trace: Array<{
    channel: string;
    score: number;
    ranks: Record<string, number>;
    documentId: string;
    title: string;
    text: string;
    section: string;
  }>;
}

export function createResearchTask(
  client: ApiClient,
  symbol: string
): Promise<WorkflowTask> {
  return client
    .POST('/api/research/tasks', { body: { symbol } })
    .then((res) => (res.data as unknown as WorkflowTask) ?? (res.error as unknown as never));
}

export function getResearchTask(
  client: ApiClient,
  taskId: string
): Promise<WorkflowTask> {
  return client
    .GET('/api/research/tasks/{taskId}', {
      params: { path: { taskId } }
    })
    .then((res) => (res.data as unknown as WorkflowTask) ?? (res.error as unknown as never));
}

export function getResearchTaskProgress(
  client: ApiClient,
  taskId: string
): Promise<WorkflowProgress> {
  return client
    .GET('/api/research/tasks/{taskId}/progress', {
      params: { path: { taskId } }
    })
    .then((res) => (res.data as unknown as WorkflowProgress) ?? (res.error as unknown as never));
}

export function latestReportTrace(
  client: ApiClient,
  symbol: string
): Promise<unknown> {
  return client
    .GET('/api/research/reports/{symbol}/trace', {
      params: { path: { symbol } }
    })
    .then((res) => res.data);
}
