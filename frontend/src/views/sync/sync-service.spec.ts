import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../../api/client';
import { executeSyncTask, loadSyncOptions, saveSyncTask } from '../../services/sync';
import { createTask } from './sync-editor';

vi.mock('../../api/client', () => ({ request: vi.fn() }));
const requestMock = vi.mocked(request);

beforeEach(() => requestMock.mockReset());

describe('同步请求契约', () => {
  it('执行明确启用幂等，保存保留原始字段与创建/更新方法', async () => {
    requestMock.mockResolvedValue({ id: 42 });
    await executeSyncTask(42);
    expect(requestMock).toHaveBeenLastCalledWith('/sync-tasks/42/execute', 'POST', undefined, {
      idempotent: true,
    });
    const task = createTask();
    await saveSyncTask(task);
    expect(requestMock).toHaveBeenLastCalledWith('/sync-tasks', 'POST', task);
    task.id = 42;
    await saveSyncTask(task);
    expect(requestMock).toHaveBeenLastCalledWith('/sync-tasks/42', 'PUT', task);
  });

  it('选项合并保持证书归属，且不修改请求返回对象', async () => {
    const own = { id: 1, commonName: 'own' };
    const customer = { id: 2, commonName: 'customer' };
    requestMock.mockResolvedValueOnce([{ id: 3 }]);
    requestMock.mockResolvedValueOnce([own]);
    requestMock.mockResolvedValueOnce([customer]);
    requestMock.mockResolvedValueOnce([{ id: 9, name: 'customer' }]);
    const options = await loadSyncOptions();
    expect(options.customers).toEqual([{ id: 9, name: 'customer' }]);
    expect(options.certificates).toEqual([
      { ...own, owner: 'OWN' },
      { ...customer, owner: 'CUSTOMER' },
    ]);
    expect(own).not.toHaveProperty('owner');
    expect(customer).not.toHaveProperty('owner');
  });
});
