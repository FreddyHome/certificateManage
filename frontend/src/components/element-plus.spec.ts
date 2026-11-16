import { describe, expect, it, vi } from 'vitest';
import { createApp } from 'vue';
import { useGlobalConfig } from 'element-plus';
import { installElementPlus } from './element-plus';

vi.mock('./element-plus-styles', () => ({}));

const templates = import.meta.glob('../**/*.vue', {
  query: '?raw',
  import: 'default',
  eager: true,
});

describe('Element Plus 按需注册', () => {
  it('覆盖所有页面模板使用的组件、loading 和独立服务，并保留中文', () => {
    const app = createApp({ render: () => null });
    installElementPlus(app);
    const tags = new Set(
      Object.values(templates).flatMap((source) =>
        [...String(source).matchAll(/<el-([a-z-]+)/g)].map((match) => match[1]!),
      ),
    );
    expect(tags.size).toBeGreaterThan(30);
    for (const tag of tags) {
      const name =
        'El' +
        tag
          .split('-')
          .map((part) => part[0]!.toUpperCase() + part.slice(1))
          .join('');
      expect(app.component(name), name).toBeDefined();
    }
    expect(app.directive('loading')).toBeDefined();
    expect(app.config.globalProperties.$message).toBeTypeOf('function');
    expect(app.config.globalProperties.$confirm).toBeTypeOf('function');
    expect(app.config.globalProperties.$prompt).toBeTypeOf('function');
    expect(useGlobalConfig('locale').value?.name).toBe('zh-cn');
    expect(app.component('ElCarousel')).toBeUndefined();
  });
});
