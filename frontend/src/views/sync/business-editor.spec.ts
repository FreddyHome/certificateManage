import { describe, expect, it } from 'vitest';
import { createBusinessStep, eligibleChains } from './business-editor';
import { stepLabel, taskPayload, createTask, parameterMaps } from './sync-editor';

describe('业务组件编辑', () => {
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
