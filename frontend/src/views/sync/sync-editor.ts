import type {
  HttpComponent,
  ParameterField,
  ParameterMaps,
  StepType,
  StoreStep,
  SyncCertificate,
  SyncStep,
  SyncTask,
} from '../../services/sync-types';
import { defaultSchedule } from './schedule-editor';

export const parameterFields: ParameterField[] = ['headers', 'fields', 'query'];
export const parameterLabels: Record<ParameterField, string> = {
  headers: '请求头 Headers',
  fields: '表单参数',
  query: '查询参数 Query',
};
export const stepTypes: StepType[] = ['KEYSTORE', 'TRUSTSTORE', 'SFTP', 'HTTP', 'SSH'];

function stepKey() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (value) =>
    value.toString(16).padStart(2, '0'),
  ).join('');
}

export function isStoreStep(step: SyncStep): step is StoreStep {
  return step.type === 'KEYSTORE' || step.type === 'TRUSTSTORE';
}

/** 只有制作证书库及读取远端文件的步骤提供可供后续消费的文件。 */
export function producesFile(step: SyncStep): boolean {
  return isStoreStep(step) || (step.type === 'SFTP' && step.config.operation === 'READ');
}

/** 读取步骤使用远端原文件名，其他文件产物使用配置的输出文件名。 */
export function outputFilename(step: SyncStep): string {
  if (step.type === 'SSH') return '';
  if (step.type === 'SFTP' && step.config.operation === 'READ') {
    return step.config.sourcePath?.split('/').pop() || '';
  }
  return step.config.filename;
}

export function createTask(): SyncTask {
  return {
    name: '',
    description: '',
    state: 'DRAFT',
    scheduleEnabled: false,
    cron: '0 0 2 * * *',
    timezone: 'Asia/Shanghai',
    schedule: defaultSchedule(),
    steps: [],
  };
}

export function createStep(type: StepType, component: HttpComponent = 'GENERIC'): SyncStep {
  const common = { key: stepKey(), name: type === 'HTTP' ? httpComponentLabel(component) : type };
  if (type === 'SSH') {
    return {
      ...common,
      name: 'Shell 命令执行',
      type,
      config: { serverId: null, command: '', timeoutSeconds: 60 },
    };
  }
  if (type === 'KEYSTORE' || type === 'TRUSTSTORE') {
    return {
      ...common,
      type,
      config: {
        format: 'PKCS12',
        filename: 'certificates.p12',
        password: '',
        keyPassword: '',
        entries: [],
      },
    };
  }
  return {
    ...common,
    type,
    config: {
      ...(type === 'HTTP' ? { httpComponent: component } : {}),
      ...(component === 'RELOAD_KEYSTORE' ? { keyStoreName: '', type: '' } : {}),
      ...(component === 'UPDATE_TN_PARTNER'
        ? { customerId: null, purpose: 'GENERAL' as const }
        : {}),
      serverId: null,
      sourceStep: '',
      certificateId: null,
      exportFormat: 'PEM',
      filename: 'certificate.pem',
      remoteDirectory: '/',
      overwrite: false,
      ...(type === 'SFTP' ? { appendTimestamp: false } : {}),
      path: '/',
      method: type === 'HTTP' ? 'GET' : 'POST',
      bodyMode: type === 'HTTP' ? 'NONE' : 'MULTIPART',
      fileField: 'file',
      headers: {},
      fields: {},
      query: {},
      body: '',
      successCodes: [200, 201, 204],
    },
  };
}

export function moveStep(steps: SyncStep[], index: number, direction: number) {
  const target = index + direction;
  if (index >= 0 && index < steps.length && target >= 0 && target < steps.length) {
    [steps[index], steps[target]] = [steps[target], steps[index]];
  }
}

export function duplicateStep(step: SyncStep): SyncStep {
  const copy: SyncStep = JSON.parse(JSON.stringify(step));
  copy.key = stepKey();
  copy.name += ' 副本';
  return copy;
}

export function parameterMaps(steps: SyncStep[]): ParameterMaps {
  const maps: ParameterMaps = {};
  for (const step of steps) {
    for (const field of parameterFields) {
      maps[step.key + field] = JSON.stringify(
        isStoreStep(step) || step.type === 'SSH' ? {} : step.config[field] || {},
        null,
        2,
      );
    }
  }
  return maps;
}

export function taskPayload(task: SyncTask, maps: ParameterMaps): SyncTask {
  const payload: SyncTask = JSON.parse(JSON.stringify(task));
  payload.scheduleEnabled = payload.state === 'READY';
  for (const step of payload.steps) {
    if (
      step.type === 'HTTP' &&
      !step.config.businessComponent &&
      (!step.config.httpComponent || step.config.httpComponent === 'GENERIC')
    ) {
      for (const field of parameterFields) {
        step.config[field] = JSON.parse(maps[step.key + field] || '{}');
      }
    }
  }
  return payload;
}

export function certificateLabel(certificate: SyncCertificate) {
  return [
    certificate.commonName || certificate.name,
    certificate.owner === 'OWN' ? '自有' : '客户',
    certificate.customer,
    certificate.serial,
  ]
    .filter(Boolean)
    .join(' · ');
}

export function syncStatus(value?: string | null) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    READY: '就绪',
    QUEUED: '排队中',
    RUNNING: '执行中',
    SUCCEEDED: '成功',
    SUCCESS: '成功',
    FAILED: '失败',
    INTERRUPTED: '已中断',
    SKIPPED: '已跳过',
  };
  return value ? labels[value] || value : value;
}

export function httpComponentLabel(component: HttpComponent = 'GENERIC') {
  return {
    GENERIC: '通用 HTTP',
    RELOAD_KEYSTORE: '重载 Keystore',
    UPDATE_TN_PARTNER: '更新 TNPartner 证书',
  }[component];
}

export function stepLabel(step: SyncStep) {
  if (step.type === 'SSH') return 'Shell 命令执行';
  if (step.type === 'HTTP' && step.config.businessComponent)
    return {
      IS_TRUSTSTORE: 'IS truststore 更新',
      IS_KEYSTORE: 'IS keyStore 更新',
      TN_CERTIFICATE: 'TN 证书更新',
    }[step.config.businessComponent];
  return step.type === 'HTTP' ? httpComponentLabel(step.config.httpComponent) : step.type;
}
