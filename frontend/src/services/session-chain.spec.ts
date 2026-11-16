import { afterEach, expect, it, vi } from 'vitest';
import { request } from '../api/client';
import { getSession, invalidateSession } from './session';
import { certificateChain, invalidateCertificateChains } from './certificate-chain';
vi.mock('../api/client', () => ({ request: vi.fn() }));
afterEach(() => {
  invalidateSession();
  invalidateCertificateChains();
  vi.clearAllMocks();
  vi.useRealTimers();
});
it('shares session requests and expires them', async () => {
  vi.useFakeTimers();
  vi.mocked(request).mockResolvedValue({ username: 'admin' });
  await Promise.all([getSession(), getSession()]);
  expect(request).toHaveBeenCalledTimes(1);
  await getSession();
  expect(request).toHaveBeenCalledTimes(1);
  vi.advanceTimersByTime(30001);
  await getSession();
  expect(request).toHaveBeenCalledTimes(2);
  invalidateSession();
  await getSession();
  expect(request).toHaveBeenCalledTimes(3);
});
it('merges chain requests and rejects invalidated old responses', async () => {
  let resolve!: (value: any) => void;
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const first = certificateChain(1);
  const second = certificateChain(1);
  expect(request).toHaveBeenCalledTimes(1);
  invalidateCertificateChains();
  resolve([{ name: 'old' }]);
  await expect(first).rejects.toThrow();
  await expect(second).rejects.toThrow();
  vi.mocked(request).mockResolvedValue([{ name: 'new' }]);
  expect(await certificateChain(1)).toEqual([{ name: 'new' }]);
});

it('bounds chain cache to 64 assets and invalidates after mutation', async () => {
  vi.mocked(request).mockResolvedValue([]);
  for (let id = 1; id <= 65; id++) await certificateChain(id);
  await certificateChain(65);
  expect(request).toHaveBeenCalledTimes(65);
  await certificateChain(1);
  expect(request).toHaveBeenCalledTimes(66);
  const { setCertificateEnabled } = await import('./certificates');
  await setCertificateEnabled(65, false);
  await certificateChain(65);
  expect(request).toHaveBeenCalledTimes(68);
});
it('does not reuse an identity invalidated during its request', async () => {
  let resolve!: (value: any) => void;
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const pending = getSession();
  invalidateSession();
  resolve({ username: 'old' });
  await expect(pending).rejects.toThrow('会话已失效');
  vi.mocked(request).mockResolvedValue({ username: 'new' });
  expect((await getSession()).username).toBe('new');
});

it('shares a slow in-flight identity past the completed-cache TTL', async () => {
  vi.useFakeTimers();
  let resolve!: (value: any) => void;
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const first = getSession();
  vi.advanceTimersByTime(30001);
  const second = getSession();
  expect(request).toHaveBeenCalledTimes(1);
  resolve({ username: 'admin' });
  await Promise.all([first, second]);
});
