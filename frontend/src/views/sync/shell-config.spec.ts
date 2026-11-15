import { describe, expect, it } from 'vitest';
import { createSSRApp, defineComponent, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import ShellStepConfig from './ShellStepConfig.vue';
import { createStep } from './sync-editor';

describe('Shell 步骤配置', () => {
  it('仅列出已启用 SSH 服务器并展示固定命令和超时配置', async () => {
    const app = createSSRApp(ShellStepConfig, {
      s: createStep('SSH'),
      servers: [
        { id: 1, type: 'SSH', enabled: true, alias: 'ssh-enabled' },
        { id: 2, type: 'SSH', enabled: false, alias: 'ssh-disabled' },
        { id: 3, type: 'SFTP', enabled: true, alias: 'sftp-server' },
        { id: 4, type: 'HTTP', enabled: true, alias: 'http-server' },
      ],
    });
    const field = defineComponent({
      props: ['label', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', {}, [props.label, props.title, slots.default?.()]),
    });
    for (const name of ['ElFormItem', 'ElSelect', 'ElInput', 'ElInputNumber', 'ElAlert']) {
      app.component(name, field);
    }
    app.component(
      'ElOption',
      defineComponent({
        props: ['label'],
        setup: (props) => () => h('option', {}, props.label),
      }),
    );
    const html = await renderToString(app);
    expect(html).toContain('ssh-enabled');
    expect(html).not.toContain('ssh-disabled');
    expect(html).not.toContain('sftp-server');
    expect(html).not.toContain('http-server');
    expect(html).toContain('Shell 命令');
    expect(html).toContain('执行超时（秒）');
    expect(html).not.toContain('文件来源');
  });
});
