import { describe, expect, it } from 'vitest';
import {
  createStep,
  createTask,
  duplicateStep,
  moveStep,
  taskPayload,
  parameterMaps,
  stepLabel,
} from './sync-editor';

describe('同步编辑规则', () => {
  it('固定 HTTP 组件保存和复制保留业务选择，不读取通用请求参数', () => {
    const reload = createStep('HTTP', 'RELOAD_KEYSTORE');
    const partner = createStep('HTTP', 'UPDATE_TN_PARTNER');
    expect(reload).toMatchObject({
      type: 'HTTP',
      name: '重载 Keystore',
      config: { httpComponent: 'RELOAD_KEYSTORE', keyStoreName: '', type: '' },
    });
    expect(partner).toMatchObject({
      type: 'HTTP',
      config: { httpComponent: 'UPDATE_TN_PARTNER', customerId: null, purpose: 'GENERAL' },
    });
    if (partner.type !== 'HTTP') throw new Error();
    partner.config.customerId = 12;
    partner.config.purpose = 'AS2';
    const copy = duplicateStep(partner);
    const task = { ...createTask(), steps: [reload, partner, copy] };
    const maps = parameterMaps(task.steps);
    maps[reload.key + 'query'] = '{invalid';
    expect(taskPayload(task, maps).steps[1].config).toMatchObject({
      customerId: 12,
      purpose: 'AS2',
    });
    expect(copy.key).not.toBe(partner.key);
    expect(copy.config).toMatchObject({
      customerId: 12,
      purpose: 'AS2',
      httpComponent: 'UPDATE_TN_PARTNER',
    });
    partner.config.customerId = 13;
    expect(copy.config).toMatchObject({ customerId: 12 });
  });

  it('旧 HTTP 无组件标识仍展示通用 HTTP 并应用自定义参数', () => {
    const legacy = createStep('HTTP');
    if (legacy.type !== 'HTTP') throw new Error();
    delete legacy.config.httpComponent;
    legacy.config.method = 'PATCH';
    legacy.config.path = '/legacy';
    expect(stepLabel(legacy)).toBe('通用 HTTP');
    const maps = parameterMaps([legacy]);
    maps[legacy.key + 'query'] = '{"custom":"value"}';
    const saved = taskPayload({ ...createTask(), steps: [legacy] }, maps).steps[0];
    expect(saved.config).toMatchObject({
      method: 'PATCH',
      path: '/legacy',
      query: { custom: 'value' },
    });
    expect(saved.config).not.toHaveProperty('httpComponent');
    expect(createStep('HTTP').config).toMatchObject({ httpComponent: 'GENERIC' });
    expect(createStep('SFTP').config).not.toHaveProperty('httpComponent');
  });
  it('复制隔离嵌套数据并分配新标识', () => {
    const source = createStep('KEYSTORE');
    if (!('entries' in source.config)) throw new Error();
    source.config.entries.push({
      assetId: 12,
      alias: 'original',
      role: 'PRIVATE',
      includeChain: false,
    });
    const copy = duplicateStep(source);
    if (!('entries' in copy.config)) throw new Error();
    copy.config.entries[0].alias = 'changed';
    expect(source.config.entries[0].alias).toBe('original');
    expect(copy.key).not.toBe(source.key);
    expect(copy.name).toBe('KEYSTORE 副本');
  });

  it('移动保持步骤对象及引用标识，边界不变化', () => {
    const steps = [createStep('KEYSTORE'), createStep('SFTP')];
    const first = steps[0];
    moveStep(steps, 0, -1);
    expect(steps[0]).toBe(first);
    moveStep(steps, 0, 1);
    expect(steps[1]).toBe(first);
    moveStep(steps, 1, 1);
    expect(steps[1]).toBe(first);
  });

  it('保存保留空密码语义，参数更新不污染编辑原对象', () => {
    const store = createStep('KEYSTORE');
    if (!('entries' in store.config)) throw new Error();
    store.config.passwordConfigured = true;
    store.config.password = '';
    store.config.keyPassword = 'new-password';
    const http = createStep('HTTP');
    const task = {
      id: 42,
      revision: 7,
      name: 'task',
      description: '',
      state: 'DRAFT' as const,
      scheduleEnabled: false,
      cron: '',
      timezone: '',
      steps: [store, http],
    };
    const maps = parameterMaps(task.steps);
    maps[http.key + 'headers'] = '{"Authorization":"value"}';
    const payload = taskPayload(task, maps);
    expect(payload).toMatchObject({ id: 42, revision: 7 });
    expect(payload.steps[0].config).toMatchObject({
      password: '',
      keyPassword: 'new-password',
      passwordConfigured: true,
    });
    expect(payload.steps[1].config).toMatchObject({ headers: { Authorization: 'value' } });
    expect(http.config).toMatchObject({ headers: {} });
    maps[http.key + 'headers'] = '{broken';
    expect(() => taskPayload(task, maps)).toThrow();
    expect(http.config).toMatchObject({ headers: {} });
  });
});
