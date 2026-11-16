import { ref, onScopeDispose } from 'vue';
import { request } from '../api/client';
import type { SyncChainNode } from './sync-types';

export const chainRevision = ref(0);
const cache = new Map<number, Promise<SyncChainNode[]>>();
let consumers = 0;
export function invalidateCertificateChains(): void {
  cache.clear();
  chainRevision.value++;
}
/** 页面编辑组件共享最多64条链，最后一个组件卸载即释放。 */
export function useCertificateChainSession(): void {
  consumers++;
  onScopeDispose(() => {
    if (--consumers === 0) invalidateCertificateChains();
  });
}
export function certificateChain(id: number): Promise<SyncChainNode[]> {
  const cached = cache.get(id);
  if (cached) return cached;
  const revision = chainRevision.value;
  const promise = request<SyncChainNode[]>(`/certificates/${id}/chain`)
    .then((chain) => {
      if (revision !== chainRevision.value) throw new Error('证书链已更新，请重新读取');
      return chain;
    })
    .catch((error) => {
      if (cache.get(id) === promise) cache.delete(id);
      throw error;
    });
  if (cache.size >= 64) cache.delete(cache.keys().next().value!);
  cache.set(id, promise);
  return promise;
}
