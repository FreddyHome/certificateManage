/** 按浏览器时区显示中文日期时间；缺失值显示占位符。 */
export function date(value: string): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}

/** 兼容现有 JDBC 响应，将记录字段名统一转换为小写。 */
export function lowerRows(rows: Record<string, unknown>[]): Record<string, unknown>[] {
  return rows.map((row) =>
    Object.fromEntries(Object.entries(row).map(([key, value]) => [key.toLowerCase(), value])),
  );
}
