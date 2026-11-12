import { afterEach, expect, it, vi } from 'vitest';
import { listCustomers, saveCustomer, type Customer } from './customers';
import { download } from './certificates';
import { request } from '../api/client';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('preserves customer response fields and create/edit request contracts', async () => {
  const customer: Customer = {
    id: 0,
    name: '客户甲',
    description: '',
    externalId: 'A',
    idType: 'ERP',
  };
  const fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('/csrf')) {
      return new Response('{"token":"customer-token","headerName":"X-CSRF-TOKEN"}');
    }
    return new Response(JSON.stringify(url.endsWith('/customers') ? [customer] : {}));
  });
  vi.stubGlobal('fetch', fetchMock);

  expect(await listCustomers()).toEqual([customer]);
  await saveCustomer(customer);
  await saveCustomer({ ...customer, id: 42 });

  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers',
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(customer),
    }),
  );
  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers/42',
    expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ ...customer, id: 42 }),
    }),
  );
});

it('keeps export password, CSRF header, filename and object URL lifecycle', async () => {
  vi.useFakeTimers();
  const fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('/csrf'))
      return new Response('{"token":"export-token","headerName":"X-CSRF-TOKEN"}');
    if (url.endsWith('/login')) return new Response('{}');
    return new Response('certificate');
  });
  vi.stubGlobal('fetch', fetchMock);
  await request('/auth/login', 'POST', {});
  const click = vi.fn();
  const anchor = { href: '', download: '', click };
  vi.stubGlobal('document', { createElement: vi.fn(() => anchor) });
  const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:certificate');
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});

  await download(7, 'P12', 'export-password');

  expect(fetchMock).toHaveBeenLastCalledWith(
    '/api/v1/certificates/7/export',
    expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'export-token' },
      body: JSON.stringify({ format: 'P12', password: 'export-password' }),
    }),
  );
  expect(create).toHaveBeenCalledWith(expect.any(Blob));
  expect(anchor).toMatchObject({ href: 'blob:certificate', download: 'certificate-7.p12' });
  expect(click).toHaveBeenCalledOnce();
  expect(revoke).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1000);
  expect(revoke).toHaveBeenCalledWith('blob:certificate');
});

it('reports export errors without creating a download', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('{"message":"证书不存在"}', { status: 422 })),
  );
  await expect(download(999, 'PEM')).rejects.toThrow('证书不存在');
});
