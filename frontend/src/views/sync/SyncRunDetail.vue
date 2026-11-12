<script setup lang="ts">
import type { SyncRunDetail, SyncRunStep } from '../../services/sync-types';
import { syncStatus as status } from './sync-editor';

defineProps<{ detail: SyncRunDetail | null }>();
const emit = defineEmits<{
  close: [];
  refresh: [id: number];
  audit: [id: number];
}>();

function resultSummary(step: SyncRunStep) {
  return (
    step.errorSummary ||
    step.errorMessage ||
    step.message ||
    step.summary ||
    step.result?.message ||
    step.result?.statusCode ||
    '—'
  );
}
</script>

<template>
  <el-dialog :model-value="!!detail" @close="emit('close')" title="执行详情" width="800px">
    <el-button @click="detail && emit('refresh', detail.id)">刷新</el-button>
    <template v-if="detail">
      <el-button @click="emit('audit', detail.id)">查看执行审计日志</el-button>
      <p>执行 #{{ detail.id }} · {{ status(detail.status) }}</p>
      <p>开始：{{ detail.startedAt || '—' }}　结束：{{ detail.finishedAt || '—' }}</p>
      <p v-if="detail.errorSummary || detail.errorMessage || detail.message">
        {{ detail.errorSummary || detail.errorMessage || detail.message }}
      </p>
      <el-table :data="detail.steps || []">
        <el-table-column prop="name" label="步骤" />
        <el-table-column prop="type" label="组件" />
        <el-table-column label="状态">
          <template #default="{ row }">{{ status(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始" />
        <el-table-column prop="finishedAt" label="结束" />
        <el-table-column label="结果">
          <template #default="{ row }">{{ resultSummary(row) }}</template>
        </el-table-column>
      </el-table>
    </template>
  </el-dialog>
</template>
