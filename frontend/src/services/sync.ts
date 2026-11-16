import { listCustomers } from './customers';
import { request } from '../api/client';
import type {
  SyncCertificate,
  SyncRun,
  SyncRunDetail,
  SyncServer,
  SyncTask,
  SyncTaskSummary,
  SyncValidation,
  ExecutionSchedule,
  SchedulePreview,
} from './sync-types';

export const previewSyncSchedule = (schedule: ExecutionSchedule) =>
  request<SchedulePreview>('/sync-tasks/schedule-preview', 'POST', schedule);

export const listSyncTasks = () => request<SyncTaskSummary[]>('/sync-tasks');
export const getSyncTask = (id: number) => request<SyncTask>(`/sync-tasks/${id}`);
export const deleteSyncTask = (id: number) => request<void>(`/sync-tasks/${id}`, 'DELETE');
export const validateSyncTask = (id: number) =>
  request<SyncValidation>(`/sync-tasks/${id}/validate`, 'POST');
export const executeSyncTask = (id: number) =>
  request<{ id: number }>(`/sync-tasks/${id}/execute`, 'POST', undefined, { idempotent: true });
export const listSyncRuns = (id: number) => request<SyncRun[]>(`/sync-tasks/${id}/runs`);
export const getSyncRun = (id: number) => request<SyncRunDetail>(`/sync-runs/${id}`);

export function saveSyncTask(task: SyncTask) {
  return request<SyncTask>(
    '/sync-tasks' + (task.id ? '/' + task.id : ''),
    task.id ? 'PUT' : 'POST',
    task,
  );
}

export async function loadSyncOptions() {
  const [servers, own, customer, customers] = await Promise.all([
    request<SyncServer[]>('/servers'),
    request<SyncCertificate[]>('/certificates?owner=OWN'),
    request<SyncCertificate[]>('/certificates?owner=CUSTOMER'),
    listCustomers(),
  ]);
  const certificates: SyncCertificate[] = [
    ...own
      .filter((certificate) => certificate.enabled !== false)
      .map((certificate) => ({ ...certificate, owner: 'OWN' as const })),
    ...customer
      .filter((certificate) => certificate.enabled !== false)
      .map((certificate) => ({ ...certificate, owner: 'CUSTOMER' as const })),
  ];
  return { servers, certificates, customers };
}
