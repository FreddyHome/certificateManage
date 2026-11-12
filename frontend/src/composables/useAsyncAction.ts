import { ElMessage } from 'element-plus';

/** 执行页面操作并统一展示错误，保留页面原有的异步调用方式。 */
export function run<T>(action: () => Promise<T>): Promise<T | void> {
  return action().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : undefined;
    ElMessage.error(message || '操作失败');
  });
}
