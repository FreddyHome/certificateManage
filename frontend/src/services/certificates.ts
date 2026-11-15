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

export interface ImportResult {
  id: number;
  importedCount: number;
  skippedCount: number;
  restoredCount: number;
}

export function commitCertificateImport(input: FormData): Promise<ImportResult> {
  return request('/imports/commit', 'POST', input, { idempotent: true });
}

/** 修改证书资产业务启用状态；查看与导出仍然可用。 */
export function setCertificateEnabled(id: number, enabled: boolean): Promise<void> {
  return request(`/certificates/${id}/enabled`, 'PATCH', { enabled });
}

export type CertificateOwner = 'OWN' | 'CUSTOMER';

export interface CertificateRow {
  id: number;
  name: string;
  owner: CertificateOwner;
  kind: string;
  purpose: string;
  customer?: string;
  serial: string;
  issuer: string;
  notAfter: string;
  status: string;
  depth: number;
  hasKey: boolean;
  enabled: boolean;
  archivedAt: string | null;
  chain?: CertificateRow[];
}

export function archiveCertificate(id: number): Promise<void> {
  return request(`/certificates/${id}/archive`, 'POST');
}

export function restoreCertificate(id: number): Promise<void> {
  return request(`/certificates/${id}/restore`, 'POST');
}

export function deleteArchivedCertificate(id: number): Promise<void> {
  return request(`/certificates/${id}`, 'DELETE');
}

export function listArchivedCertificates(owner?: CertificateOwner): Promise<CertificateRow[]> {
  return request(`/certificates/archived${owner ? `?owner=${owner}` : ''}`);
}
