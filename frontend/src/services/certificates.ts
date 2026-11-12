import { request, requestBlob } from '../api/client';

/** 导出证书并触发浏览器下载，下载后释放临时对象地址。 */
export async function download(id: number, format: string, password = ''): Promise<void> {
  const blob = await requestBlob(`/certificates/${id}/export`, { format, password }, '导出失败');
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `certificate-${id}.${format.toLowerCase()}`;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 签发属于业务幂等写操作，由业务服务明确声明。 */
export function issueCertificate(input: Record<string, unknown>): Promise<{ id: number }> {
  return request('/certificates/issue', 'POST', input, { idempotent: true });
}

export interface ImportPreview {
  chain: string[];
  keyMatched: boolean;
}

export function previewCertificateImport(input: FormData): Promise<ImportPreview> {
  return request('/imports/preview', 'POST', input);
}

export function commitCertificateImport(input: FormData): Promise<{ id: number }> {
  return request('/imports/commit', 'POST', input, { idempotent: true });
}
