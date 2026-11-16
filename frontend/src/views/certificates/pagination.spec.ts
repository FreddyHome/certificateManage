import { afterEach, describe, expect, it, vi } from 'vitest';
import { effectScope, reactive } from 'vue';
import * as certificates from '../../services/certificates';
import { request } from '../../api/client';
import { useCertificatePagination } from './pagination';
import type { CertificatePage, CertificatePageFilters } from '../../services/certificates';

vi.mock('../../api/client', () => ({ request: vi.fn(), requestBlob: vi.fn() }));

const response = (name: string, total = 1, page = 1): CertificatePage => ({
  items: [
    {
      id: 1,
      name,
      owner: 'OWN',
      kind: 'LEAF',
      purpose: 'GENERAL',
      serial: '1',
      issuer: 'CA',
      notAfter: '2030-01-01T00:00:00Z',
      status: '有效',
      depth: 1,
      hasKey: true,
      enabled: true,
      archivedAt: null,
    },
  ],
  total,
  page,
  size: 20,
});
const scopes: ReturnType<typeof effectScope>[] = [];
function setup(
  loader: (filters: CertificatePageFilters) => Promise<CertificatePage>,
  grouped = false,
) {
  const filters = reactive<CertificatePageFilters>({
    owner: 'OWN',
    query: '',
    purpose: '',
    status: '',
  });
  const scope = effectScope();
  scopes.push(scope);
  const errors: unknown[] = [];
  const pagination = scope.run(() =>
    useCertificatePagination(
      loader,
      () => ({ ...filters }),
      () => grouped && filters.owner === 'OWN',
      (action) =>
        action().catch((error) => {
          errors.push(error);
        }),
    ),
  )!;
  return { filters, pagination, errors, scope };
}
async function settle() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop());
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('certificate page HTTP contract', () => {
  it('sends every server filter and preserves literal search characters', async () => {
    vi.mocked(request).mockResolvedValue(response('result'));
    await certificates.listCertificatePage({
      owner: 'CUSTOMER',
      kind: 'LEAF',
      query: '甲 %_&',
      purpose: 'AS2',
      status: '有效',
      page: 3,
      size: 20,
    });
    const url = new URL(vi.mocked(request).mock.calls[0][0], 'http://local');
    expect(url.pathname).toBe('/certificates/page');
    const params: Record<string, string> = {};
    url.searchParams.forEach((value, key) => {
      params[key] = value;
    });
    expect(params).toEqual({
      owner: 'CUSTOMER',
      kind: 'LEAF',
      query: '甲 %_&',
      purpose: 'AS2',
      status: '有效',
      page: '3',
      size: '20',
    });
  });
  it('uses archived page endpoint with defaults and omits blank optional filters', async () => {
    await certificates.listArchivedCertificatePage({ query: '', owner: '' });
    expect(vi.mocked(request).mock.calls[0]).toEqual([
      '/certificates/archived/page?page=1&size=20',
    ]);
  });
});

describe('certificate pagination state', () => {
  it('loads three independent own pages and retains filters on pagination', async () => {
    const calls: CertificatePageFilters[] = [];
    const { filters, pagination } = setup(async (f) => {
      calls.push(f);
      return response(f.kind!, 62, f.page);
    }, true);
    await settle();
    expect(calls.map((f) => [f.kind, f.page, f.size])).toEqual([
      ['ROOT', 1, 20],
      ['INTERMEDIATE', 1, 20],
      ['LEAF', 1, 20],
    ]);
    filters.purpose = 'AS2';
    await settle();
    await pagination.changePage('ROOT', 2);
    expect(pagination.tables.ROOT.page).toBe(2);
    expect(pagination.tables.INTERMEDIATE.page).toBe(1);
    expect(pagination.tables.LEAF.page).toBe(1);
    expect(calls.at(-1)).toMatchObject({
      owner: 'OWN',
      purpose: 'AS2',
      kind: 'ROOT',
      page: 2,
      size: 20,
    });
    filters.status = '有效';
    await settle();
    expect(pagination.tables.ROOT.page).toBe(1);
    expect(calls.slice(-3).map((f) => [f.kind, f.page, f.purpose, f.status])).toEqual([
      ['ROOT', 1, 'AS2', '有效'],
      ['INTERMEDIATE', 1, 'AS2', '有效'],
      ['LEAF', 1, 'AS2', '有效'],
    ]);
  });
  it('debounces search for 300ms, resets pages and invalidates old responses immediately', async () => {
    vi.useFakeTimers();
    let resolveOld!: (page: CertificatePage) => void;
    const calls: CertificatePageFilters[] = [];
    const { filters, pagination } = setup((f) => {
      calls.push(f);
      return calls.length === 1
        ? new Promise((resolve) => {
            resolveOld = resolve;
          })
        : Promise.resolve(response(f.query!));
    });
    filters.query = 'a';
    resolveOld(response('stale'));
    await settle();
    expect(pagination.tables.main.items).toEqual([]);
    await vi.advanceTimersByTimeAsync(200);
    filters.query = 'ab';
    await vi.advanceTimersByTimeAsync(299);
    expect(calls).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls).toHaveLength(2);
    expect(pagination.tables.main.items[0].name).toBe('ab');
    expect(calls[1]).toMatchObject({ query: 'ab', page: 1, size: 20 });
  });
  it('rejects stale page successes and failures without hiding the latest loading state', async () => {
    const pending: { resolve: (value: CertificatePage) => void; reject: (error: Error) => void }[] =
      [];
    const { pagination, errors } = setup(
      () =>
        new Promise((resolve, reject) => {
          pending.push({ resolve, reject });
        }),
    );
    const second = pagination.changePage('main', 2);
    pending[0].reject(new Error('obsolete'));
    await settle();
    expect(errors).toEqual([]);
    expect(pagination.tables.main.loading).toBe(true);
    const third = pagination.changePage('main', 3);
    pending[2].resolve(response('latest', 60, 3));
    await third;
    pending[1].resolve(response('old', 60, 2));
    await second;
    expect(pagination.tables.main).toMatchObject({ page: 3, total: 60, loading: false });
    expect(pagination.tables.main.items[0].name).toBe('latest');
  });
  it('reloads the last valid page after archive/restore/delete removes its final row', async () => {
    const calls: number[] = [];
    let removed = false;
    const { pagination } = setup(async (f) => {
      calls.push(f.page!);
      return removed && f.page === 3
        ? { items: [], total: 20, page: 3, size: 20 }
        : response('kept', removed ? 20 : 41, f.page);
    });
    await settle();
    await pagination.changePage('main', 3);
    removed = true;
    await pagination.load();
    expect(calls).toEqual([1, 3, 3, 1]);
    expect(pagination.tables.main).toMatchObject({ page: 1, total: 20, loading: false });
    expect(pagination.tables.main.items[0].name).toBe('kept');
  });
  it('invalidates all owner pages when switching owner and cancels pending work on unmount', async () => {
    vi.useFakeTimers();
    const calls: CertificatePageFilters[] = [];
    const { filters, pagination, scope } = setup(async (f) => {
      calls.push(f);
      return response(f.owner!);
    }, true);
    await settle();
    filters.owner = 'CUSTOMER';
    await settle();
    expect(calls.at(-1)).toMatchObject({ owner: 'CUSTOMER', page: 1 });
    expect(calls.at(-1)?.kind).toBeUndefined();
    expect(pagination.tables.main.items[0].name).toBe('CUSTOMER');
    filters.query = 'cancelled';
    scope.stop();
    await vi.advanceTimersByTimeAsync(300);
    expect(calls).toHaveLength(4);
  });
  it('keeps an empty archive on page one after removing the only remaining row', async () => {
    let removed = false;
    const { pagination } = setup(async () =>
      removed ? { items: [], total: 0, page: 1, size: 20 } : response('last archive'),
    );
    await settle();
    removed = true;
    await pagination.load();
    expect(pagination.tables.main).toMatchObject({ items: [], total: 0, page: 1, loading: false });
  });
  it('reports the active page failure and clears its loading indicator', async () => {
    const error = new Error('列表读取失败');
    const { pagination, errors } = setup(async () => {
      throw error;
    });
    await settle();
    expect(errors).toEqual([error]);
    expect(pagination.tables.main.loading).toBe(false);
  });
});
