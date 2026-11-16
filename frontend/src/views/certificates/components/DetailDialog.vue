<script setup lang="ts">
import { ref, watch, onScopeDispose } from 'vue';
import { request } from '../../../api/client';
import { run } from '../../../composables/useAsyncAction';
import {
  certificateChain,
  chainRevision,
  useCertificateChainSession,
} from '../../../services/certificate-chain';
useCertificateChainSession();
import { date } from '../../../utils/format';
const props = defineProps<{ id?: number }>();
const emit = defineEmits(['closed']);
const detail = ref<any>(),
  chain = ref<any[]>([]),
  selected = ref<any>();
let generation = 0;
onScopeDispose(() => {
  generation++;
});
watch(
  () => [props.id, chainRevision.value] as const,
  ([id]) => {
    const current = ++generation;
    detail.value = undefined;
    chain.value = [];
    selected.value = undefined;
    if (id)
      run(async () => {
        const [nextDetail, nextChain] = await Promise.all([
          request('/certificates/' + id),
          certificateChain(id),
        ]);
        if (current !== generation) return;
        detail.value = nextDetail;
        chain.value = nextChain;
        selected.value = nextChain.at(-1);
      });
  },
  { immediate: true },
);
</script>
<template>
  <el-dialog :model-value="!!id" title="证书详情" width="800px" @close="emit('closed')"
    ><template v-if="detail"
      ><h2>{{ detail.name }}</h2>
      <p class="sub">
        {{ detail.purpose }} · {{ detail.status }} ·
        {{
          detail.owner === 'OWN'
            ? detail.hasKey
              ? '私钥已加密保存'
              : '仅公钥，无签发私钥'
            : '客户公钥证书'
        }}
      </p>
      <h3>证书链（根 → 当前证书）</h3>
      <div class="chain">
        <template v-for="(node, i) in chain" :key="i"
          ><span v-if="i">→</span
          ><el-button :type="selected === node ? 'primary' : 'default'" @click="selected = node"
            >{{ node.name }} · 第{{ node.depth }}层</el-button
          ></template
        >
      </div>
      <el-descriptions v-if="selected" :column="1" border
        ><el-descriptions-item label="名称 CN">{{ selected.name }}</el-descriptions-item
        ><el-descriptions-item label="Subject">{{ selected.subject }}</el-descriptions-item
        ><el-descriptions-item label="Issuer">{{ selected.issuer }}</el-descriptions-item
        ><el-descriptions-item label="SHA-256">{{ selected.fingerprint }}</el-descriptions-item
        ><el-descriptions-item label="到期时间">{{
          date(selected.notAfter)
        }}</el-descriptions-item></el-descriptions
      ><el-descriptions v-if="selected" :column="1" border style="margin-top: 16px"
        ><el-descriptions-item label="当前证书生效时间">{{
          date(selected.notBefore)
        }}</el-descriptions-item
        ><el-descriptions-item label="公钥 / 签名算法"
          >{{ selected.algorithm }} / {{ selected.signatureAlgorithm }}</el-descriptions-item
        ><el-descriptions-item label="序列号">{{ selected.serial }}</el-descriptions-item
        ><el-descriptions-item label="SAN">{{
          selected.san || '—'
        }}</el-descriptions-item></el-descriptions
      >
      <p class="sub" style="margin-top: 16px">
        证书链完整性不代表目标系统已配置信任；撤销状态未检查。
      </p></template
    ></el-dialog
  >
</template>
