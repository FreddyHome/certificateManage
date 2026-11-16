<script setup lang="ts">
import { useRouter } from 'vue-router';
import { run } from '../../composables/useAsyncAction';
import SyncTaskEditor from './SyncTaskEditor.vue';
import SyncRunHistory from './SyncRunHistory.vue';
import SyncRunDetail from './SyncRunDetail.vue';
import { syncStatus as status } from './sync-editor';
import { formatScheduleTime } from './schedule-editor';
import { useSyncTasks } from './useSyncTasks';

const router = useRouter();
const {
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
} = useSyncTasks();

function viewAudit(id: number) {
  router.push({ path: '/system/audit', query: { runId: id } });
  detail.value = null;
  history.value = null;
}
</script>

<template>
  <div class="page-heading">
    <h1>证书同步</h1>
    <el-button type="primary" @click="edit()">新增任务</el-button>
  </div>
  <div class="panel">
    <el-table :data="rows">
      <el-table-column prop="name" label="任务" />
      <el-table-column label="状态">
        <template #default="{ row }">{{ status(row.state) }}</template>
      </el-table-column>
      <el-table-column
        prop="scheduleSummary"
        label="执行计划"
        min-width="240"
        show-overflow-tooltip
      />
      <el-table-column label="最近状态">
        <template #default="{ row }">{{ status(row.lastStatus) }}</template>
      </el-table-column>
      <el-table-column label="下次计划时间" min-width="210"
        ><template #default="{ row }">{{
          row.scheduleEnabled
            ? formatScheduleTime(row.nextFireAt, row.timezone, row.schedule?.frequency === 'CUSTOM')
            : '—'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button link @click="edit(row)">编辑</el-button>
          <el-button link :disabled="row.state !== 'READY'" @click="execute(row)">执行</el-button>
          <el-button link @click="runs(row)">记录</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
  <SyncTaskEditor
    :form="form"
    :servers="servers"
    :certs="certs"
    :customers="customers"
    :maps="maps"
    :validation="validation"
    @close="form = null"
    @save="run(save)"
    @validate="validate"
    @add="add"
    @add-business="addBusiness"
    @move="move"
    @duplicate="duplicate"
  />
  <SyncRunHistory :history="history" @close="history = null" @view="viewRun" />
  <SyncRunDetail :detail="detail" @close="detail = null" @refresh="viewRun" @audit="viewAudit" />
</template>
