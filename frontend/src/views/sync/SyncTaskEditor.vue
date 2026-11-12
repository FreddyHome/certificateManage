<script setup lang="ts">
import { computed } from 'vue';
import FixedHttpStepConfig from './FixedHttpStepConfig.vue';
import StoreStepConfig from './StoreStepConfig.vue';
import TransferStepConfig from './TransferStepConfig.vue';
import { isStoreStep, stepTypes, stepLabel } from './sync-editor';
import type {
  HttpComponent,
  SyncCustomer,
  ParameterMaps,
  StepType,
  SyncCertificate,
  SyncServer,
  SyncStep,
  SyncTask,
  SyncValidation,
} from '../../services/sync-types';

const props = defineProps<{
  form: SyncTask | null;
  servers: SyncServer[];
  certs: SyncCertificate[];
  customers: SyncCustomer[];
  maps: ParameterMaps;
  validation: SyncValidation | null;
}>();
const emit = defineEmits<{
  close: [];
  save: [];
  validate: [];
  add: [type: StepType, component?: HttpComponent];
  move: [index: number, direction: number];
  duplicate: [step: SyncStep];
}>();
const validationRows = computed(
  () =>
    props.validation?.entries?.flatMap((group) =>
      (group.entries || []).map((entry) => ({ ...entry, stepName: group.step })),
    ) || [],
);
</script>

<template>
  <el-dialog :model-value="!!form" @close="emit('close')" title="同步流程" width="1000px">
    <el-form v-if="form" label-position="top">
      <el-form-item label="名称">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.state">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="就绪" value="READY" />
        </el-select>
      </el-form-item>
      <el-switch v-model="form.scheduleEnabled" active-text="启用定时" />
      <el-form-item label="六字段 Cron">
        <el-input v-model="form.cron" />
      </el-form-item>
      <el-form-item label="时区">
        <el-input v-model="form.timezone" />
      </el-form-item>
      <el-card v-for="(s, i) in form.steps" :key="s.key" style="margin: 16px 0">
        <template #header>
          <b>{{ Number(i) + 1 }} · {{ stepLabel(s) }}</b>
          <el-button text @click="emit('move', Number(i), -1)">上移</el-button>
          <el-button text @click="emit('move', Number(i), 1)">下移</el-button>
          <el-button text @click="emit('duplicate', s)">复制</el-button>
          <el-button text type="danger" @click="form.steps.splice(i, 1)">移除</el-button>
        </template>
        <el-form-item label="步骤名称">
          <el-input v-model="s.name" />
        </el-form-item>
        <StoreStepConfig v-if="isStoreStep(s)" :s="s" :certs="certs" />
        <FixedHttpStepConfig
          v-else-if="
            s.type === 'HTTP' && s.config.httpComponent && s.config.httpComponent !== 'GENERIC'
          "
          :s="s"
          :servers="servers"
          :customers="customers"
        />
        <TransferStepConfig
          v-else
          :s="s"
          :i="Number(i)"
          :form="form"
          :servers="servers"
          :certs="certs"
          :maps="maps"
        />
      </el-card>
      <el-button v-for="t in stepTypes" :key="t" @click="emit('add', t)"
        >添加 {{ t === 'HTTP' ? '通用 HTTP' : t }}</el-button
      >
      <el-button @click="emit('add', 'HTTP', 'RELOAD_KEYSTORE')">添加重载 Keystore</el-button>
      <el-button @click="emit('add', 'HTTP', 'UPDATE_TN_PARTNER')"
        >添加更新 TNPartner 证书</el-button
      >
      <section v-if="validation">
        <el-alert
          :title="validation.valid ? '校验通过' : '校验失败'"
          :type="validation.valid ? 'success' : 'error'"
          :closable="false"
        />
        <p>下次定时执行：{{ (validation.nextTimes || []).join('、') || '未启用定时' }}</p>
        <el-table v-if="validation.entries" :data="validationRows">
          <el-table-column prop="stepName" label="步骤" />
          <el-table-column prop="alias" label="别名" />
          <el-table-column prop="assetId" label="证书ID" />
          <el-table-column prop="role" label="角色" />
        </el-table>
      </section>
    </el-form>
    <template #footer>
      <el-button @click="emit('validate')">保存并校验</el-button>
      <el-button type="primary" @click="emit('save')">保存</el-button>
    </template>
  </el-dialog>
</template>
