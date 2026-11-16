import { onMounted, ref } from 'vue';
import { createBusinessStep } from './business-editor';
import { invalidateCertificateChains } from '../../services/certificate-chain';
import type { BusinessComponent } from '../../services/sync-types';
import { ElMessage, ElMessageBox } from 'element-plus';
import { run } from '../../composables/useAsyncAction';
import {
  deleteSyncTask,
  executeSyncTask,
  getSyncRun,
  getSyncTask,
  listSyncRuns,
  listSyncTasks,
  loadSyncOptions,
  saveSyncTask,
  validateSyncTask,
} from '../../services/sync';
import type {
  HttpComponent,
  SyncCustomer,
  ParameterMaps,
  StepType,
  SyncCertificate,
  SyncRun,
  SyncRunDetail,
  SyncServer,
  SyncStep,
  SyncTask,
  SyncTaskSummary,
  SyncValidation,
} from '../../services/sync-types';
import {
  createStep,
  createTask,
  duplicateStep,
  moveStep,
  parameterFields,
  parameterMaps,
  taskPayload,
} from './sync-editor';

export function useSyncTasks() {
  const rows = ref<SyncTaskSummary[]>([]);
  const servers = ref<SyncServer[]>([]);
  const customers = ref<SyncCustomer[]>([]);
  const certs = ref<SyncCertificate[]>([]);
  const form = ref<SyncTask | null>(null);
  const history = ref<SyncRun[] | null>(null);
  const detail = ref<SyncRunDetail | null>(null);
  const validation = ref<SyncValidation | null>(null);
  const maps = ref<ParameterMaps>({});

  async function load() {
    rows.value = await listSyncTasks();
  }

  async function loadOptions() {
    const options = await loadSyncOptions();
    customers.value = options.customers;
    servers.value = options.servers;
    certs.value = options.certificates;
  }

  onMounted(() => Promise.all([run(load), run(loadOptions)]));

  function edit(row?: SyncTaskSummary) {
    invalidateCertificateChains();
    return run(async () => {
      form.value = row ? await getSyncTask(row.id) : createTask();
      maps.value = parameterMaps(form.value.steps);
      validation.value = null;
    });
  }

  function add(type: StepType, component: HttpComponent = 'GENERIC') {
    if (!form.value) return;
    const step = createStep(type, component);
    form.value.steps.push(step);
    Object.assign(maps.value, parameterMaps([step]));
  }

  function move(index: number, direction: number) {
    if (form.value) moveStep(form.value.steps, index, direction);
  }

  function addBusiness(component: BusinessComponent) {
    if (form.value) form.value.steps.push(createBusinessStep(component));
  }

  function duplicate(step: SyncStep) {
    if (!form.value) return;
    const copy = duplicateStep(step);
    form.value.steps.push(copy);
    for (const field of parameterFields) {
      maps.value[copy.key + field] = maps.value[step.key + field] || '{}';
    }
  }

  async function save() {
    if (!form.value) return;
    form.value = await saveSyncTask(taskPayload(form.value, maps.value));
    await load();
    ElMessage.success('已保存');
  }

  function validate() {
    return run(async () => {
      await save();
      if (form.value?.id) validation.value = await validateSyncTask(form.value.id);
    });
  }

  function viewRun(id: number) {
    return run(async () => {
      detail.value = await getSyncRun(id);
    });
  }

  function execute(row: SyncTaskSummary) {
    return run(async () => {
      const execution = await executeSyncTask(row.id);
      detail.value = await getSyncRun(execution.id);
    });
  }

  function runs(row: SyncTaskSummary) {
    return run(async () => {
      history.value = await listSyncRuns(row.id);
    });
  }

  async function remove(row: SyncTaskSummary) {
    try {
      await ElMessageBox.confirm('删除任务 ' + row.name + '？', '确认');
    } catch {
      return;
    }
    return run(async () => {
      await deleteSyncTask(row.id);
      await load();
    });
  }

  return {
    rows,
    servers,
    certs,
    customers,
    form,
    history,
    detail,
    validation,
    maps,
    edit,
    add,
    addBusiness,
    move,
    duplicate,
    save,
    validate,
    execute,
    runs,
    remove,
    viewRun,
  };
}
