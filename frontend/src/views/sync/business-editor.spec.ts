import { describe, expect, it } from 'vitest';
import { createBusinessStep, eligibleChains, eligibleOwnChains } from './business-editor';
import { stepLabel, taskPayload, createTask, parameterMaps } from './sync-editor';

describe('业务组件编辑', () => {
  it('自有私钥库按叶子CN精确匹配并按到期时间和ID排序，失效基准仍可用于匹配', () => {
    const base = {
      owner: 'OWN' as const,
      kind: 'LEAF',
      name: 'service.example',
      hasKey: true,
      notBefore: '2020-01-01',
      notAfter: '2030-01-01',
    };
    const rows = [
      { ...base, id: 1, chainAvailable: false },
      { ...base, id: 2 },
      { ...base, id: 3, notAfter: '2031-01-01' },
      { ...base, id: 4, notAfter: '2031-01-01' },
      { ...base, id: 5, name: 'other.example' },
      { ...base, id: 6, hasKey: false },
      { ...base, id: 7, enabled: false },
      { ...base, id: 8, archivedAt: '2026-01-01' },
      { ...base, id: 9, notBefore: '2099-01-01' },
      { ...base, id: 10, kind: 'ROOT' },
      { ...base, id: 11, owner: 'CUSTOMER' as const },
      { ...base, id: 12, name: 'SERVICE.example' },
    ];
    expect(eligibleOwnChains(rows, 1).map((c) => c.id)).toEqual([4, 3, 2]);
    expect(eligibleOwnChains(rows, null)).toEqual([]);
    expect(eligibleOwnChains(rows, 99, 'service.example').map((c) => c.id)).toEqual([4, 3, 2]);
  });
  it('三个业务组件保存默认来源、接口和固定类型且保留用户请求参数', () => {
    const trust = createBusinessStep('IS_TRUSTSTORE');
    expect(stepLabel(trust)).toBe('IS truststore 更新');
    expect(trust.config.sourceType).toBe('SFTP');
    expect(trust.config.sourceMethod).toBe('GET');
    expect(trust.config.sourceHeaders).toEqual({ 'Content-Type': 'application/json' });
    expect(trust.config.path).toBe('/restv2/wm.certificate:cert/keystore/update');
    expect(trust.config.selectionMode).toBe('AUTO');
    trust.config.sourceQuery = { extra: 'value' };
    expect(
      taskPayload({ ...createTask(), steps: [trust] }, parameterMaps([trust])).steps[0]?.config,
    ).toMatchObject({ sourceQuery: { extra: 'value' } });
    expect(createBusinessStep('IS_KEYSTORE').config.type).toBe('key');
    expect(createBusinessStep('IS_KEYSTORE').config.alias).toBe('');
    expect(trust.config.alias).toBeUndefined();
    expect(createBusinessStep('IS_KEYSTORE').config.sourceServerId).toBeUndefined();
    expect(createBusinessStep('IS_KEYSTORE').config.storePath).toBe('');
    expect(createBusinessStep('IS_KEYSTORE').config.format).toBe('PKCS12');
    expect(createBusinessStep('TN_CERTIFICATE').config.path).toBe(
      '/restv2/wm.certificate:cert/tn/update',
    );
  });

  it('仅选择客户用途相符且整链可用的终端证书，按到期最晚排序', () => {
    const base = {
      owner: 'CUSTOMER' as const,
      customerId: 5,
      purpose: 'AS2',
      kind: 'LEAF',
      enabled: true,
      notBefore: '2020-01-01',
      notAfter: '2030-01-01',
    };
    const rows = [
      { ...base, id: 1 },
      { ...base, id: 2, notAfter: '2031-01-01' },
      { ...base, id: 3, customerId: 9 },
      { ...base, id: 4, enabled: false },
      { ...base, id: 5, chainAvailable: false },
    ];
    expect(eligibleChains(rows, 5, 'AS2').map((c) => c.id)).toEqual([2, 1]);
  });
});
