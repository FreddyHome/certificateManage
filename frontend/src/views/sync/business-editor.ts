import type { BusinessComponent, SyncCertificate, TransferStep } from '../../services/sync-types';
import { createStep } from './sync-editor';

export const businessLabels: Record<BusinessComponent, string> = {
  IS_TRUSTSTORE: 'IS truststore 更新',
  IS_KEYSTORE: 'IS keyStore 更新',
  TN_CERTIFICATE: 'TN 证书更新',
};

export function createBusinessStep(component: BusinessComponent): TransferStep {
  const step = createStep('HTTP') as TransferStep;
  step.name = businessLabels[component];
  Object.assign(step.config, {
    businessComponent: component,
    ...(component === 'IS_TRUSTSTORE'
      ? {
          sourceType: 'SFTP',
          sourceServerId: null,
          sourceUrl: '/restv2/wm.certificate:cert/keystore/get',
          sourceMethod: 'GET',
          sourcePath: '',
          sourceHeaders: { 'Content-Type': 'application/json' },
          sourceQuery: {},
        }
      : {}),
    ...(component === 'IS_KEYSTORE' ? { format: 'PKCS12', storePath: '', alias: '' } : {}),
    selectionMode: 'AUTO',
    customerId: null,
    purpose: 'GENERAL',
    deleteExpired: false,
    password: '',
    keyPassword: '',
    keyStoreName: '',
    type: component === 'IS_KEYSTORE' ? 'key' : 'trust',
    path:
      component === 'TN_CERTIFICATE'
        ? '/restv2/wm.certificate:cert/tn/update'
        : '/restv2/wm.certificate:cert/keystore/update',
  });
  return step;
}

export function eligibleChains(
  certs: SyncCertificate[],
  customerId: number | null | undefined,
  purpose: string | undefined,
): SyncCertificate[] {
  const now = Date.now();
  return certs
    .filter(
      (c) =>
        c.owner === 'CUSTOMER' &&
        c.customerId === customerId &&
        c.purpose === purpose &&
        c.kind === 'LEAF' &&
        c.enabled !== false &&
        c.chainAvailable !== false &&
        !c.archivedAt &&
        Date.parse(c.notBefore || '') <= now &&
        Date.parse(c.notAfter || '') > now,
    )
    .sort((a, b) => Date.parse(b.notAfter || '') - Date.parse(a.notAfter || '') || b.id - a.id);
}
