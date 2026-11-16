import { onScopeDispose, reactive, watch } from 'vue';
import type {
  CertificatePage,
  CertificatePageFilters,
  CertificateRow,
} from '../../services/certificates';

export const certificateKinds = ['ROOT', 'INTERMEDIATE', 'LEAF'] as const;
export type CertificateTable = (typeof certificateKinds)[number] | 'main';
interface PageState {
  items: CertificateRow[];
  total: number;
  page: number;
  loading: boolean;
}

export function useCertificatePagination(
  loader: (filters: CertificatePageFilters) => Promise<CertificatePage>,
  filters: () => CertificatePageFilters,
  grouped: () => boolean,
  perform: (action: () => Promise<void>) => unknown,
) {
  const keys: CertificateTable[] = [...certificateKinds, 'main'];
  const tables = reactive(
    keys.reduce(
      (states, key) => {
        states[key] = { items: [], total: 0, page: 1, loading: false };
        return states;
      },
      {} as Record<CertificateTable, PageState>,
    ),
  );
  const generations: Record<CertificateTable, number> = {
    ROOT: 0,
    INTERMEDIATE: 0,
    LEAF: 0,
    main: 0,
  };
  let timer: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;

  async function changePage(key: CertificateTable, page: number) {
    if (disposed) return;
    const state = tables[key];
    const generation = ++generations[key];
    const snapshot = { ...filters(), kind: key === 'main' ? undefined : key, size: 20 };
    state.page = page;
    state.loading = true;
    try {
      while (generation === generations[key]) {
        const result = await loader({ ...snapshot, page });
        if (generation !== generations[key]) return;
        const lastPage = Math.max(1, Math.ceil(result.total / 20));
        if (page > lastPage) {
          page = lastPage;
          state.page = page;
          continue;
        }
        state.items = result.items;
        state.total = result.total;
        state.page = page;
        return;
      }
    } catch (error) {
      if (generation === generations[key]) throw error;
    } finally {
      if (generation === generations[key]) state.loading = false;
    }
  }

  async function load() {
    clearTimeout(timer);
    await Promise.all(
      (grouped() ? [...certificateKinds] : (['main'] as const)).map((key) =>
        changePage(key, tables[key].page),
      ),
    );
  }

  watch(
    filters,
    (next, previous) => {
      clearTimeout(timer);
      for (const key of keys) {
        ++generations[key];
        Object.assign(tables[key], { items: [], total: 0, page: 1, loading: false });
      }
      if (previous && next.query !== previous.query) {
        timer = setTimeout(() => perform(load), 300);
      } else {
        perform(load);
      }
    },
    { immediate: true, deep: true, flush: 'sync' },
  );

  onScopeDispose(() => {
    disposed = true;
    clearTimeout(timer);
    for (const key of keys) ++generations[key];
  });
  return { tables, load, changePage };
}
