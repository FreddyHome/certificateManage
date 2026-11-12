import { afterEach, expect, it, vi } from 'vitest';
import { request, requestBlob } from './client';
import { issueCertificate } from '../services/certificates';

afterEach(() => vi.unstubAllGlobals());

it('refreshes CSRF before every login after a previous session', async () => {
  let count = 0;
  const sent: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, options: RequestInit) => {
      if (url.endsWith('/csrf'))
        return Response.json({ token: 'csrf-' + ++count, headerName: 'X-CSRF-TOKEN' });
      sent.push((options.headers as Record<string, string>)['X-CSRF-TOKEN']);
      return Response.json({ username: 'admin' });
    }),
  );
  await request('/auth/login', 'POST', {});
  await request('/auth/login', 'POST', {});
  expect(sent).toEqual(['csrf-1', 'csrf-2']);
});

it('lets the certificate service request idempotency without secure-context randomUUID', async () => {
  vi.stubGlobal('crypto', {
    getRandomValues: (a: Uint8Array) => {
      a.fill(4);
      return a;
    },
  });
  const sent: Record<string, string>[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, options: RequestInit) => {
      if (url.endsWith('/csrf')) return Response.json({ token: 'x', headerName: 'X-CSRF-TOKEN' });
      sent.push(options.headers as Record<string, string>);
      return Response.json({ id: 1 });
    }),
  );
  await issueCertificate({});
  await request('/ordinary-write', 'POST', {});
  expect(sent[0]['X-Idempotency-Key']).toHaveLength(32);
  expect(sent[1]['X-Idempotency-Key']).toBeUndefined();
});

it('handles binary 401 responses with the same login redirect and fallback message', async () => {
  vi.stubGlobal('location', { hash: '/certificates/own' });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/csrf')) return Response.json({ token: 'x', headerName: 'X-CSRF-TOKEN' });
      if (url.endsWith('/login')) return Response.json({});
      return new Response('<html>Unauthorized</html>', { status: 401 });
    }),
  );
  await request('/auth/login', 'POST', {});
  await expect(requestBlob('/binary', {}, '导出失败')).rejects.toThrow('导出失败');
  expect(location.hash).toBe('/login');
});

it('does not replay writes when the server rejects a request', async () => {
  let writes = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/csrf')) return Response.json({ token: 'x', headerName: 'X-CSRF-TOKEN' });
      if (url.endsWith('/login')) return Response.json({});
      writes++;
      return Response.json({ message: '请求被拒绝' }, { status: 403 });
    }),
  );
  await request('/auth/login', 'POST', {});
  await expect(request('/write', 'POST', {})).rejects.toThrow('请求被拒绝');
  expect(writes).toBe(1);
});
