import { afterEach, expect, it, vi } from 'vitest';
import { request } from '../api/client';
import { getSession, invalidateSession } from './session';
vi.stubGlobal('location', { hash: '' });
afterEach(() => {
  vi.unstubAllGlobals();
  invalidateSession();
});
it('invalidates a cached identity after any API 401', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'admin' })))
    .mockResolvedValueOnce(new Response('{}', { status: 401 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'next' })));
  vi.stubGlobal('fetch', fetcher);
  vi.stubGlobal('location', { hash: '' });
  await getSession();
  await expect(request('/certificates')).rejects.toThrow();
  expect((await getSession()).username).toBe('next');
  expect(fetcher).toHaveBeenCalledTimes(3);
});
it.each([
  ['/auth/login', 'POST'],
  ['/auth/logout', 'POST'],
  ['/auth/password', 'PUT'],
])('invalidates a cached identity after %s', async (path, method) => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'admin' })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'x', headerName: 'X-CSRF-TOKEN' })))
    .mockResolvedValueOnce(new Response('{}'))
    .mockResolvedValueOnce(new Response(JSON.stringify({ username: 'new' })));
  vi.stubGlobal('fetch', fetcher);
  await getSession();
  await request(path, method);
  expect((await getSession()).username).toBe('new');
});
