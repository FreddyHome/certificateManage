import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../../api/client';
import {
  archiveCertificate,
  deleteArchivedCertificate,
  listArchivedCertificates,
  restoreCertificate,
} from '../../services/certificates';
vi.mock('../../api/client', () => ({ request: vi.fn(), requestBlob: vi.fn() }));
describe('certificate archive service contract', () => {
  beforeEach(() => vi.clearAllMocks());
  it('omits owner for all archives and supports both ownership filters', async () => {
    await listArchivedCertificates();
    await listArchivedCertificates('OWN');
    await listArchivedCertificates('CUSTOMER');
    expect(vi.mocked(request).mock.calls).toEqual([
      ['/certificates/archived'],
      ['/certificates/archived?owner=OWN'],
      ['/certificates/archived?owner=CUSTOMER'],
    ]);
  });
  it('uses archive and restore transitions without changing enabled state', async () => {
    await archiveCertificate(7);
    await restoreCertificate(7);
    await deleteArchivedCertificate(7);
    expect(vi.mocked(request).mock.calls).toEqual([
      ['/certificates/7/archive', 'POST'],
      ['/certificates/7/restore', 'POST'],
      ['/certificates/7', 'DELETE'],
    ]);
  });
});
