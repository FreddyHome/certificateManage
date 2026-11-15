<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { request } from '../../api/client';
import { date } from '../../utils/format';
import { bodyText, payloadMetadata } from './audit-details';
import type { AuditDetail, OriginalPayload } from './audit-details';

const props = defineProps<{ id: number }>();
const emit = defineEmits<{ close: [] }>();
const loading = ref(true);
const error = ref('');
const detail = ref<AuditDetail | null>(null);
const originals = ref<Record<string, OriginalPayload>>({});
const busy = ref<Record<string, boolean>>({});
const failures = ref<Record<string, string>>({});
const views = ref<Record<string, 'text' | 'base64'>>({});
const bodyCache = ref<Record<string, string>>({});

onMounted(async () => {
  try {
    detail.value = await request<AuditDetail>(`/execution-logs/${props.id}`);
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '详情读取失败';
  } finally {
    loading.value = false;
  }
});

async function loadOriginal(id: string) {
  if (originals.value[id] || busy.value[id]) return;
  busy.value[id] = true;
  delete failures.value[id];
  try {
    const data = await request<{ payload: OriginalPayload }>(
      `/execution-logs/${props.id}/raw/${id}`,
    );
    originals.value[id] = data.payload;
    views.value[id] = 'text';
  } catch (failure) {
    failures.value[id] = failure instanceof Error ? failure.message : '原始内容读取失败';
  } finally {
    busy.value[id] = false;
  }
}

function originalBody(id: string) {
  const payload = originals.value[id];
  if (!payload) return '';
  if (views.value[id] === 'base64') return payload.bodyBase64 || '';
  if (!(id in bodyCache.value)) bodyCache.value[id] = bodyText(payload);
  return bodyCache.value[id];
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败，请选择文本手动复制');
  }
}
</script>

<template>
  <el-drawer
    :model-value="true"
    title="审计日志详情"
    size="min(1100px, 95vw)"
    @close="emit('close')"
  >
    <div v-loading="loading" class="audit-detail">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="detail">
        <h3>{{ detail.entry.message }}</h3>
        <p class="detail-context">
          条目 #{{ id }}
          <template v-if="detail.entry.runId"> · 执行 #{{ detail.entry.runId }}</template
          ><template v-if="detail.entry.stepKey"> · 步骤 {{ detail.entry.stepKey }}</template>
        </p>
        <el-alert
          v-if="!detail.hasOriginals"
          title="此条记录尚无原始详情。历史未采集的报文和堆栈无法补回。"
          type="info"
          :closable="false"
        />
        <el-tabs>
          <el-tab-pane :label="`原始执行日志（${detail.rawEvents.length}）`">
            <el-timeline class="raw-timeline">
              <el-timeline-item
                v-for="event in detail.rawEvents"
                :key="event.id"
                :timestamp="date(event.occurredAt)"
                placement="top"
                :type="event.kind === 'EXCEPTION' ? 'danger' : 'primary'"
              >
                <div class="raw-event-heading">
                  <div>
                    <b>{{ event.title }}</b><el-tag size="small" type="info">{{
                      event.kind
                    }}</el-tag>
                  </div>
                  <span>{{ (event.payloadBytes / 1024).toFixed(1) }} KB</span>
                </div>
                <el-button
                  v-if="!originals[event.id]"
                  :loading="busy[event.id]"
                  size="small"
                  @click="loadOriginal(event.id)"
                  >查看原始内容</el-button
                >
                <p v-if="failures[event.id]" class="raw-error">{{ failures[event.id] }}</p>
                <template v-if="originals[event.id]">
                  <el-alert
                    v-if="originals[event.id]?.complete === false"
                    title="此次传输因超时、异常或大小限制未完整接收，下方保存的是实际已接收的全部字节。"
                    type="warning"
                    :closable="false"
                  />
                  <div class="raw-section-heading">
                    <b>原始信息 / 请求与响应头</b
                    ><el-button
                      text
                      size="small"
                      @click="copy(JSON.stringify(payloadMetadata(originals[event.id]!), null, 2))"
                      >复制</el-button
                    >
                  </div>
                  <pre>{{ JSON.stringify(payloadMetadata(originals[event.id]!), null, 2) }}</pre>
                  <template v-if="originals[event.id]?.bodyBase64 !== undefined">
                    <div class="raw-section-heading">
                      <b>正文 / 文件内容</b
                      ><el-radio-group v-model="views[event.id]" size="small"
                        ><el-radio-button value="text">原始文本</el-radio-button
                        ><el-radio-button value="base64"
                          >Base64 字节</el-radio-button
                        ></el-radio-group
                      ><el-button text size="small" @click="copy(originalBody(event.id))"
                        >复制</el-button
                      >
                    </div>
                    <pre class="body-view">{{ originalBody(event.id) || '（空正文）' }}</pre>
                    <p class="raw-note">
                      文本按响应编码解码，保留原始空格和换行；二进制文件及字节完整性请查看 Base64。
                    </p>
                  </template>
                  <template v-if="originals[event.id]?.stackTrace">
                    <div class="raw-section-heading">
                      <b>完整原始异常堆栈</b
                      ><el-button
                        text
                        size="small"
                        @click="copy(String(originals[event.id]?.stackTrace))"
                        >复制</el-button
                      >
                    </div>
                    <pre class="stack-view">{{ originals[event.id]?.stackTrace }}</pre>
                  </template>
                </template>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
          <el-tab-pane :label="`关联摘要（${detail.events.length}）`">
            <el-timeline class="raw-timeline"
              ><el-timeline-item
                v-for="event in detail.events"
                :key="event.id"
                :timestamp="date(event.occurredAt)"
                placement="top"
                ><b>{{ event.eventCode }}</b>
                <p>{{ event.message }}</p></el-timeline-item
              ></el-timeline
            >
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.audit-detail {
  min-height: 120px;
}
.detail-context,
.raw-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.raw-timeline {
  padding: 16px 12px 0 4px;
}
.raw-event-heading,
.raw-section-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 8px 0;
  flex-wrap: wrap;
}
.raw-event-heading {
  justify-content: space-between;
}
.raw-event-heading .el-tag {
  margin-left: 10px;
}
.raw-event-heading > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
pre {
  max-height: 360px;
  overflow: auto;
  margin: 8px 0 18px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font:
    12px/1.7 Consolas,
    monospace;
  white-space: pre;
}
.body-view,
.stack-view {
  max-height: 500px;
}
.raw-error {
  color: var(--el-color-danger);
}
</style>
