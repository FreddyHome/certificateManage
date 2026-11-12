<script setup lang="ts">
import type { SyncRun } from '../../services/sync-types';
import { syncStatus as status } from './sync-editor';

defineProps<{ history: SyncRun[] | null }>();
const emit = defineEmits<{ close: []; view: [id: number] }>();
</script>

<template>
  <el-dialog :model-value="history !== null" @close="emit('close')" title="执行记录" width="850px">
    <el-table :data="history || []">
      <el-table-column prop="id" label="执行ID" />
      <el-table-column label="状态">
        <template #default="{ row }">{{ status(row.status) }}</template>
      </el-table-column>
      <el-table-column prop="startedAt" label="开始时间" />
      <el-table-column label="详情">
        <template #default="{ row }">
          <el-button link @click="emit('view', row.id)">查看 / 刷新</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>
