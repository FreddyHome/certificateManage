import { invalidateCertificateChains } from './certificate-chain';
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
export async function issueCertificate(input: Record<string, unknown>): Promise<{ id: number }> {
  const result = await request('/certificates/issue', 'POST', input, { idempotent: true });
  invalidateCertificateChains();
  return result;
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

export async function commitCertificateImport(input: FormData): Promise<ImportResult> {
  const result = await request('/imports/commit', 'POST', input, { idempotent: true });
  invalidateCertificateChains();
  return result;
}

/** 修改证书资产业务启用状态；查看与导出仍然可用。 */
export async function setCertificateEnabled(id: number, enabled: boolean): Promise<void> {
  const result = await request(`/certificates/${id}/enabled`, 'PATCH', { enabled });
  invalidateCertificateChains();
  return result;
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

export async function archiveCertificate(id: number): Promise<void> {
  const result = await request(`/certificates/${id}/archive`, 'POST');
  invalidateCertificateChains();
  return result;
}

export async function restoreCertificate(id: number): Promise<void> {
  const result = await request(`/certificates/${id}/restore`, 'POST');
  invalidateCertificateChains();
  return result;
}

export async function deleteArchivedCertificate(id: number): Promise<void> {
  const result = await request(`/certificates/${id}`, 'DELETE');
  invalidateCertificateChains();
  return result;
}

export function listArchivedCertificates(owner?: CertificateOwner): Promise<CertificateRow[]> {
  return request(`/certificates/archived${owner ? `?owner=${owner}` : ''}`);
}

export interface CertificatePageFilters {
  owner?: CertificateOwner | '';
  kind?: string;
  query?: string;
  purpose?: string;
  status?: string;
  page?: number;
  size?: number;
}

export interface CertificatePage {
  items: CertificateRow[];
  total: number;
  page: number;
  size: number;
}

function certificatePage(path: string, filters: CertificatePageFilters): Promise<CertificatePage> {
  const params = new URLSearchParams();
  for (const key of ['owner', 'kind', 'query', 'purpose', 'status'] as const) {
    if (filters[key]) params.set(key, filters[key]);
  }
  params.set('page', String(filters.page ?? 1));
  params.set('size', String(filters.size ?? 20));
  return request(`${path}?${params}`);
}

export function listCertificatePage(
  filters: CertificatePageFilters = {},
): Promise<CertificatePage> {
  return certificatePage('/certificates/page', filters);
}

export function listArchivedCertificatePage(
  filters: CertificatePageFilters = {},
): Promise<CertificatePage> {
  return certificatePage('/certificates/archived/page', filters);
}
