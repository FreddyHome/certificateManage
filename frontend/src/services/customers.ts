import { request } from '../api/client';

export interface Customer {
  id: number;
  name: string;
  description: string;
  externalId: string;
  idType: string;
}

/** 获取按归一化名称排序的客户列表。 */
export function listCustomers(): Promise<Customer[]> {
  return request<Customer[]>('/customers');
}

/** id 为 0 时新增，其余情况编辑；保留现有接口与请求字段。 */
export async function saveCustomer(customer: Customer): Promise<void> {
  const path = customer.id ? `/customers/${customer.id}` : '/customers';
  await request(path, customer.id ? 'PATCH' : 'POST', customer);
}
