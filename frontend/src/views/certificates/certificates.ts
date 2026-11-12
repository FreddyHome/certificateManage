interface FilterableCertificate {
  id: number;
  name: string;
  notAfter: string;
  purpose: string;
  customer?: string;
}

/** 按用途和名称筛选，保留同名证书并按到期时间、ID稳定排序。 */
export function filteredCertificates<T extends FilterableCertificate>(
  rows: T[],
  query: string,
  purpose: string,
): T[] {
  const normalizedQuery = query.toLowerCase();
  return rows
    .filter(
      (row) =>
        (!purpose || row.purpose === purpose) &&
        `${row.name} ${row.customer || ''}`.toLowerCase().includes(normalizedQuery),
    )
    .sort((a, b) => a.notAfter.localeCompare(b.notAfter) || a.id - b.id);
}
