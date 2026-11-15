export type OriginalPayload = Record<string, unknown> & {
  bodyBase64?: string;
  stackTrace?: string;
};

/** 保留原始空格和换行；字节完整性以同时提供的 Base64 为准。 */
export function bodyText(payload: OriginalPayload): string {
  const binary = atob(payload.bodyBase64 || '');
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  const headers = payload.headers as Record<string, string[] | string> | undefined;
  const contentType = Object.entries(headers || {}).find(
    ([name]) => name.toLowerCase() === 'content-type',
  )?.[1];
  const charset = String(contentType || '').match(/charset\s*=\s*"?([^;\s"]+)/i)?.[1] || 'utf-8';
  try {
    return new TextDecoder(charset).decode(bytes);
  } catch {
    return new TextDecoder().decode(bytes);
  }
}

export function payloadMetadata(payload: OriginalPayload): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(payload).filter(([key]) => key !== 'bodyBase64' && key !== 'stackTrace'),
  );
}

export interface OriginalEvent {
  id: string;
  kind: string;
  title: string;
  occurredAt: string;
  payloadBytes: number;
}

export interface AuditDetail {
  entry: { id: number; message: string; runId?: number; stepKey?: string; source: string };
  hasOriginals: boolean;
  rawEvents: OriginalEvent[];
  events: { id: number; message: string; occurredAt: string; eventCode: string; outcome: string }[];
}
