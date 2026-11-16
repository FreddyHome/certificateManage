import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../../api/client';
import { loadSyncOptions } from '../../services/sync';
import { useSyncTasks } from './useSyncTasks';
import { createTask } from './sync-editor';

const lifecycle = vi.hoisted(() => ({ mounted: undefined as undefined | (() => unknown) }));
vi.mock('vue', async (original) => ({
  ...(await original<typeof import('vue')>()),
  onMounted: (callback: () => unknown) => {
    lifecycle.mounted = callback;
  },
}));
vi.mock('../../api/client', () => ({ request: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}));
const requestMock = vi.mocked(request);

beforeEach(() => {
  requestMock.mockReset();
  lifecycle.mounted = undefined;
});

describe('同步请求并发与保存复用', () => {
  it('服务器响应挂起时也立即请求全部证书和客户选项', async () => {
    let release!: (value: unknown) => void;
    requestMock.mockImplementation((path) =>
      path === '/servers'
        ? (new Promise((resolve) => {
            release = resolve;
          }) as never)
        : (Promise.resolve([]) as never),
    );
    const pending = loadSyncOptions();
    expect(requestMock.mock.calls.map(([path]) => path)).toEqual([
      '/servers',
      '/certificates?owner=OWN',
      '/certificates?owner=CUSTOMER',
      '/customers',
    ]);
    release([]);
    await pending;
  });

  it('列表挂起时立即加载选项，选项可以独立显示', async () => {
    let release!: (value: unknown) => void;
    requestMock.mockImplementation((path) =>
      path === '/sync-tasks'
        ? (new Promise((resolve) => {
            release = resolve;
          }) as never)
        : (Promise.resolve(path === '/servers' ? [{ id: 9 }] : []) as never),
    );
    const state = useSyncTasks();
    const pending = lifecycle.mounted!();
    await vi.waitFor(() => expect(state.servers.value).toEqual([{ id: 9 }]));
    expect(state.rows.value).toEqual([]);
    release([]);
    await pending;
  });

  it('保存直接显示返回的任务，不再重新 GET 详情', async () => {
    const saved = { ...createTask(), id: 42, name: '服务端返回名称' };
    requestMock.mockImplementation(
      (path, method) =>
        Promise.resolve(
          method === 'POST' ? saved : path === '/sync-tasks' ? [] : undefined,
        ) as never,
    );
    const state = useSyncTasks();
    state.form.value = createTask();
    await state.save();
    expect(state.form.value).toEqual(saved);
    expect(requestMock.mock.calls.map(([path]) => path)).toEqual(['/sync-tasks', '/sync-tasks']);
  });
});
