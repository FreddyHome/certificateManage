import type { Customer } from './customers';

export type SyncCustomer = Customer;
export type HttpComponent = 'GENERIC' | 'RELOAD_KEYSTORE' | 'UPDATE_TN_PARTNER';
export type BusinessComponent = 'IS_TRUSTSTORE' | 'IS_KEYSTORE' | 'TN_CERTIFICATE';

export type StepType = 'KEYSTORE' | 'TRUSTSTORE' | 'SFTP' | 'HTTP' | 'SSH';
export type ParameterField = 'headers' | 'fields' | 'query';
export type ParameterMaps = Record<string, string>;
export type Parameters = Record<string, unknown>;

export interface StoreEntry {
  assetId: number | null;
  alias: string;
  role: 'PRIVATE' | 'TRUST';
  includeChain: boolean;
}

export interface StoreConfig {
  format: string;
  filename: string;
  password?: string;
  keyPassword?: string;
  passwordConfigured?: boolean;
  keyPasswordConfigured?: boolean;
  entries: StoreEntry[];
}

export interface TransferConfig {
  businessComponent?: BusinessComponent;
  alias?: string;
  format?: string;
  storePath?: string;
  sourceType?: 'SFTP' | 'HTTP';
  sourceServerId?: number | null;
  sourceUrl?: string;
  sourceMethod?: string;
  sourceHeaders?: Parameters;
  sourceQuery?: Parameters;
  selectionMode?: 'AUTO' | 'MANUAL';
  materialIds?: number[];
  deleteExpired?: boolean;
  password?: string;
  keyPassword?: string;
  passwordConfigured?: boolean;
  keyPasswordConfigured?: boolean;
  httpComponent?: HttpComponent;
  keyStoreName?: string;
  type?: string;
  customerId?: number | null;
  purpose?: 'AS2' | 'HTTPS' | 'GENERAL';
  serverId: number | null;
  sourceStep: string;
  certificateId: number | null;
  exportFormat: string;
  filename: string;
  remoteDirectory: string;
  overwrite: boolean;
  operation?: 'UPLOAD' | 'MOVE' | 'READ';
  sourcePath?: string;
  targetPath?: string;
  appendTimestamp?: boolean;
  path: string;
  method: string;
  bodyMode: string;
  fileField: string;
  headers: Parameters;
  fields: Parameters;
  query: Parameters;
  body: string;
  successCodes: (number | string)[];
  jsonPath?: string;
  expectedValue?: string;
}

export interface StoreStep {
  key: string;
  name: string;
  type: 'KEYSTORE' | 'TRUSTSTORE';
  config: StoreConfig;
}

export interface TransferStep {
  key: string;
  name: string;
  type: 'SFTP' | 'HTTP';
  config: TransferConfig;
}

export interface ShellStep {
  key: string;
  name: string;
  type: 'SSH';
  config: { serverId: number | null; command: string; timeoutSeconds: number };
}

export type SyncStep = StoreStep | TransferStep | ShellStep;

export interface SyncTaskSummary {
  id: number;
  name: string;
  description: string;
  revision: number;
  state: 'DRAFT' | 'READY';
  scheduleEnabled: boolean;
  cron: string;
  timezone: string;
  lastStatus?: string | null;
  nextFireAt?: string | null;
}

export interface SyncTask extends Omit<SyncTaskSummary, 'id' | 'revision'> {
  id?: number;
  revision?: number;
  steps: SyncStep[];
}

export interface SyncServer {
  config?: { baseUrl?: string };
  id: number;
  type: string;
  enabled: boolean;
  alias: string;
}

export interface SyncCertificate {
  id: number;
  commonName?: string;
  name?: string;
  owner: 'OWN' | 'CUSTOMER';
  customer?: string;
  serial?: string;
  hasKey?: boolean;
  enabled?: boolean;
  customerId?: number | null;
  purpose?: string;
  kind?: string;
  notBefore?: string;
  notAfter?: string;
  archivedAt?: string | null;
  chainAvailable?: boolean;
}

export interface SyncChainNode {
  materialId: number;
  name: string;
  kind: string;
  serial: string;
  notBefore: string;
  notAfter: string;
}

export interface ValidationEntry {
  assetId: number;
  alias: string;
  role: string;
}

export interface SyncValidation {
  valid: boolean;
  nextTimes?: string[];
  entries?: { step: string; entries?: ValidationEntry[] }[];
}

export interface RunStatus {
  status: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  errorSummary?: string | null;
  errorMessage?: string | null;
  message?: string | null;
}

export interface SyncRun extends RunStatus {
  id: number;
}

export interface SyncRunStep extends RunStatus {
  key: string;
  name: string;
  type: StepType;
  summary?: string;
  result?: { message?: string; statusCode?: number } | null;
}

export interface SyncRunDetail extends SyncRun {
  steps?: SyncRunStep[];
}
