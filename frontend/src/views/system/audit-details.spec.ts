import { describe, expect, it } from 'vitest';
import { bodyText, payloadMetadata } from './audit-details';

describe('原始审计详情', () => {
  it('保持原始正文的空格换行及Unicode，不自动格式化或过滤', () => {
    const original = '{\n  "value": "原始内容", "token": "raw-token"\n}';
    const encoded = btoa(String.fromCharCode(...new TextEncoder().encode(original)));
    expect(bodyText({ bodyBase64: encoded })).toBe(original);
  });
  it('元数据单独展示，正文和堆栈不重复展开', () => {
    const payload = {
      url: '/api',
      bodyBase64: 'YQ==',
      stackTrace: 'at method()',
      headers: { Authorization: ['Bearer original'] },
    };
    expect(payloadMetadata(payload)).toEqual({
      url: '/api',
      headers: { Authorization: ['Bearer original'] },
    });
    expect(bodyText({ bodyBase64: '' })).toBe('');
  });
});
