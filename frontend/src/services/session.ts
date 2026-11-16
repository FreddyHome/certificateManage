import { request } from '../api/client';
import { sessionGeneration } from './session-state';
export { invalidateSession } from './session-state';

let entry:
  { generation: number; expires: number; promise: Promise<{ username: string }> } | undefined;
/** 短期复用会话和在途请求；所有业务 API 仍由服务端独立鉴权。 */
export function getSession(): Promise<{ username: string }> {
  if (entry && entry.generation === sessionGeneration && entry.expires > Date.now())
    return entry.promise;
  const generation = sessionGeneration;
  const promise = request<{ username: string }>('/auth/me')
    .then((user) => {
      if (generation !== sessionGeneration) throw new Error('会话已失效');
      if (entry?.promise === promise) entry.expires = Date.now() + 30000;
      return user;
    })
    .catch((error) => {
      if (entry?.promise === promise) entry = undefined;
      throw error;
    });
  entry = { generation, expires: Infinity, promise };
  return promise;
}
