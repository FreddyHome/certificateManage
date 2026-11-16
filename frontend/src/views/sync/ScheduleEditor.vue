<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { SyncTask } from '../../services/sync-types';
import { previewSyncSchedule } from '../../services/sync';
import {
  calendarSchedule,
  defaultSchedule,
  createPreviewController,
  formatScheduleTime,
} from './schedule-editor';
import type { SchedulePreviewState } from './schedule-editor';
const props = defineProps<{ task: SyncTask }>();
const advanced = ref<string[]>([]);
watch(
  () => props.task,
  (task) => {
    task.schedule = calendarSchedule(
      task.schedule || { frequency: 'CUSTOM', cron: task.cron, timezone: task.timezone },
    );
  },
  { immediate: true },
);
const schedule = computed(() => props.task.schedule!);
const state = ref<SchedulePreviewState>({ status: 'idle' });
const preview = createPreviewController(previewSyncSchedule, (value) => {
  state.value = value;
});
watch(
  () => props.task.schedule,
  () => {
    preview.refresh(schedule.value, true);
  },
  { deep: true, immediate: true },
);
onBeforeUnmount(() => preview.dispose());
type SelectionKey = 'months' | 'monthDays' | 'weekdays' | 'hours' | 'minutes';
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const columns: {
  key: SelectionKey;
  label: string;
  values: number[];
  display: (value: number) => string;
}[] = [
  {
    key: 'months',
    label: '月份',
    values: Array.from({ length: 12 }, (_, i) => i + 1),
    display: (v) => `${v}月`,
  },
  {
    key: 'monthDays',
    label: '日期',
    values: Array.from({ length: 31 }, (_, i) => i + 1),
    display: (v) => `${v}日`,
  },
  {
    key: 'weekdays',
    label: '星期',
    values: Array.from({ length: 7 }, (_, i) => i + 1),
    display: (v) => weekdays[v - 1]!,
  },
  {
    key: 'hours',
    label: '小时',
    values: Array.from({ length: 24 }, (_, i) => i),
    display: (v) => String(v).padStart(2, '0'),
  },
  {
    key: 'minutes',
    label: '分钟',
    values: Array.from({ length: 60 }, (_, i) => i),
    display: (v) => String(v).padStart(2, '0'),
  },
];
const zoneOptions = [
  { value: 'Asia/Shanghai', label: '北京时间（UTC+08:00）' },
  { value: 'UTC', label: '协调世界时（UTC）' },
  { value: 'Asia/Hong_Kong', label: '香港（Asia/Hong_Kong）' },
  { value: 'Asia/Singapore', label: '新加坡（Asia/Singapore）' },
  { value: 'Asia/Tokyo', label: '东京（Asia/Tokyo）' },
  { value: 'Europe/London', label: '伦敦（Europe/London）' },
  { value: 'Europe/Paris', label: '巴黎（Europe/Paris）' },
  { value: 'America/New_York', label: '纽约（America/New_York）' },
  { value: 'America/Los_Angeles', label: '洛杉矶（America/Los_Angeles）' },
];
function useCalendar() {
  props.task.schedule = { ...defaultSchedule(), timezone: schedule.value.timezone };
}
function clearColumn(key: SelectionKey) {
  schedule.value[key] = [];
}
function updateColumn(key: SelectionKey, values: unknown) {
  schedule.value[key] = values as number[];
}
function selectionCount(key: SelectionKey) {
  return schedule.value[key]?.length || 0;
}
</script>
<template>
  <section class="schedule-panel" aria-label="执行计划">
    <div class="schedule-heading">
      <h3>执行计划</h3>
      <p>在各列中勾选执行时间，可多选；需要立即执行时，使用任务列表中的“执行”按钮。</p>
    </div>
    <p v-if="task.state !== 'READY'" class="draft-note">
      草稿仅保存配置。任务设为“就绪”并保存后，将按下面的计划执行。
    </p>
    <p v-else class="schedule-note">保存后，任务将按此计划自动执行。</p>
    <template v-if="schedule.frequency === 'CUSTOM'">
      <el-alert title="已有计划包含特殊规则，原执行时间会保留。" type="info" :closable="false" />
      <el-button class="convert-button" @click="useCalendar">改用月份、日期和时间选择</el-button>
      <el-collapse v-model="advanced"
        ><el-collapse-item name="rule" title="高级设置 · 原执行规则">
          <el-form-item label="调度表达式（六字段）"
            ><el-input v-model="schedule.cron" maxlength="500"
          /></el-form-item> </el-collapse-item
      ></el-collapse>
    </template>
    <template v-else>
      <div class="calendar-grid" aria-label="日期和时间选择">
        <div v-for="column in columns" :key="column.key" class="calendar-column">
          <div class="column-heading">
            <strong>{{ column.label }}</strong><span>{{
              selectionCount(column.key) ? `已选 ${selectionCount(column.key)} 项` : '不限'
            }}</span>
          </div>
          <el-button
            class="any-button"
            :type="selectionCount(column.key) ? 'default' : 'primary'"
            plain
            size="small"
            @click="clearColumn(column.key)"
            >不限</el-button
          >
          <el-checkbox-group
            :model-value="schedule[column.key] || []"
            :aria-label="column.label"
            class="column-options"
            @update:model-value="updateColumn(column.key, $event)"
          >
            <el-checkbox v-for="value in column.values" :key="value" :value="value">{{
              column.display(value)
            }}</el-checkbox>
          </el-checkbox-group>
        </div>
      </div>
      <p class="schedule-note">
        未选具体值表示该列不限。日期与星期同时选择时，需同时满足；没有所选日期的月份会跳过。按星期执行不自动处理法定节假日和调休。
      </p>
    </template>
    <el-form-item label="执行时区" class="zone-field"
      ><el-select v-model="schedule.timezone" filterable>
        <el-option
          v-for="zone in zoneOptions"
          :key="zone.value"
          :value="zone.value"
          :label="zone.label"
        />
        <el-option
          v-if="!zoneOptions.some((zone) => zone.value === schedule.timezone)"
          :value="schedule.timezone"
          :label="schedule.timezone"
        /> </el-select
    ></el-form-item>
    <div class="schedule-preview" aria-live="polite">
      <p v-if="state.status === 'loading'" class="schedule-note">正在更新执行规则和计划时间…</p>
      <el-alert
        v-else-if="state.status === 'error'"
        :title="state.message"
        type="error"
        :closable="false"
      />
      <template v-else-if="state.status === 'success'">
        <strong>{{ state.preview.summary }}</strong>
        <p class="preview-label">
          最近三次计划时间{{ task.state !== 'READY' ? '（草稿预览，尚未启用）' : '' }}
        </p>
        <ol>
          <li v-for="time in state.preview.nextTimes" :key="time">
            {{ formatScheduleTime(time, schedule.timezone, schedule.frequency === 'CUSTOM') }}
          </li>
        </ol>
      </template>
    </div>
    <p class="schedule-note">
      前次任务未结束时本次不会并行执行，失败不自动重试。停机遗漏不会逐次补跑；恢复时可能处理一次已到期触发。
    </p>
  </section>
</template>
<style scoped>
.schedule-panel {
  margin: 12px 0 24px;
  padding: 20px 24px;
  border: 1px solid #dce3ec;
  border-radius: 8px;
  background: #f8fafc;
}
.schedule-heading h3 {
  font-size: 16px;
  margin: 0 0 6px;
  color: #24364b;
}
.schedule-heading p,
.schedule-note {
  color: #68778a;
  font-size: 12px;
  line-height: 1.7;
  margin: 0;
}
.draft-note {
  font-size: 12px;
  color: #947022;
  margin: 12px 0;
}
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(110px, 1fr));
  gap: 12px;
  margin: 20px 0 12px;
}
.calendar-column {
  min-width: 0;
  border: 1px solid #dce3ec;
  border-radius: 6px;
  background: white;
  overflow: hidden;
}
.column-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 4px;
  background: #edf2f7;
  padding: 10px;
}
.column-heading strong {
  color: #24364b;
  font-size: 13px;
}
.column-heading span {
  color: #68778a;
  font-size: 11px;
}
.any-button {
  width: calc(100% - 16px);
  margin: 8px;
}
.column-options {
  height: 244px;
  overflow-y: auto;
  padding: 0 8px 8px;
  display: flex;
  flex-direction: column;
  scrollbar-width: thin;
}
.column-options :deep(.el-checkbox) {
  margin: 0;
  flex-shrink: 0;
  height: 29px;
  padding: 0 6px;
  border-radius: 3px;
}
.column-options :deep(.el-checkbox:hover) {
  background: #f0f5fa;
}
.column-options :deep(.el-checkbox.is-checked) {
  background: #eaf2fc;
}
.column-options :deep(.el-checkbox__label) {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.zone-field {
  max-width: 360px;
  margin-top: 18px;
}
.schedule-preview {
  margin: 18px 0 12px;
  border-left: 3px solid #4b769f;
  background: white;
  padding: 16px 20px;
  color: #24364b;
  overflow-wrap: anywhere;
}
.schedule-preview strong {
  font-size: 14px;
}
.preview-label {
  color: #68778a;
  margin: 14px 0 8px;
  font-size: 12px;
}
.schedule-preview ol {
  margin: 0;
  padding-left: 22px;
  font-size: 13px;
  line-height: 2;
  font-variant-numeric: tabular-nums;
}
.convert-button {
  margin: 12px 0;
}
@media (max-width: 700px) {
  .schedule-panel {
    padding: 16px;
  }
  .calendar-grid {
    overflow-x: auto;
    grid-template-columns: repeat(5, 120px);
  }
}
</style>
