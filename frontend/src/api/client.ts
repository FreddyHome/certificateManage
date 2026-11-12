export interface RequestOptions {
  /** 由业务服务显式开启，基础传输层不识别业务路径。 */
  idempotent?: boolean;
}

let token = '';
let header = 'X-CSRF-TOKEN';

async function assertSuccess(response: Response, path: string, fallback: string): Promise<void> {
  if (response.ok) return;
  let message = fallback;
  try {
    const data: unknown = await response.json();
    if (
      data &&
      typeof data === 'object' &&
      'message' in data &&
      typeof data.message === 'string' &&
      data.message
    ) {
      message = data.message;
    }
  } catch {
    // 代理或容器可能返回 HTML 错误页，沿用调用方的安全提示。
  }
  if (response.status === 401) {
    token = '';
    if (path !== '/auth/login' && typeof location !== 'undefined') location.hash = '/login';
  }
  throw new Error(message);
}

export async function csrf(): Promise<void> {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
  await assertSuccess(response, '/auth/csrf', '无法获取安全令牌');
  const data = await response.json();
  token = data.token;
  header = data.headerName;
}

async function send(
  path: string,
  method: string,
  body: unknown,
  options: RequestOptions,
  fallback: string,
): Promise<Response> {
  if (method !== 'GET' && (!token || path === '/auth/login')) await csrf();
  const headers: Record<string, string> = {};
  if (method !== 'GET') headers[header] = token;
  if (options.idempotent) {
    headers['X-Idempotency-Key'] = Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) =>
      byte.toString(16).padStart(2, '0'),
    ).join('');
  }
  if (body !== undefined && !(body instanceof FormData))
    headers['Content-Type'] = 'application/json';
  const response = await fetch('/api/v1' + path, {
    method,
    credentials: 'same-origin',
    headers,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  await assertSuccess(response, path, fallback);
  if (path === '/auth/logout') token = '';
  return response;
}

export async function request<T = any>(
  path: string,
  method = 'GET',
  body?: unknown,
  options: RequestOptions = {},
): Promise<T> {
  const response = await send(path, method, body, options, '请求失败');
  if (response.status === 204 || response.headers.get('content-length') === '0')
    return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** 二进制与 JSON 请求共用会话、CSRF 和错误处理，不自动重试远端写操作。 */
export async function requestBlob(
  path: string,
  body: unknown,
  errorMessage = '请求失败',
): Promise<Blob> {
  return (await send(path, 'POST', body, {}, errorMessage)).blob();
}
