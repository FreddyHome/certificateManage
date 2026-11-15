<script setup lang="ts">
import type { StoreStep, SyncCertificate } from '../../services/sync-types';
import { certificateLabel as label } from './sync-editor';

const props = defineProps<{ s: StoreStep; certs: SyncCertificate[] }>();

function addEntry() {
  props.s.config.entries.push({
    assetId: null,
    alias: '',
    role: props.s.type === 'KEYSTORE' ? 'PRIVATE' : 'TRUST',
    includeChain: false,
  });
}
</script>

<template>
  <el-select v-model="s.config.format">
    <el-option v-for="f in ['JKS', 'PKCS12']" :key="f" :value="f" :label="f" />
  </el-select>
  <el-form-item label="输出文件名">
    <el-input v-model="s.config.filename" />
  </el-form-item>
  <el-form-item :label="s.config.passwordConfigured ? 'Store 密码（留空保留）' : 'Store 密码'">
    <el-input v-model="s.config.password" type="password" show-password />
  </el-form-item>
  <el-form-item v-if="s.type === 'KEYSTORE'" label="私钥密码（现有密码留空保留）">
    <el-input v-model="s.config.keyPassword" type="password" show-password />
  </el-form-item>
  <div v-for="(e, j) in s.config.entries" :key="j" style="margin: 12px 0">
    <el-select v-model="e.role">
      <el-option v-if="s.type === 'KEYSTORE'" label="私钥" value="PRIVATE" />
      <el-option label="信任证书" value="TRUST" />
    </el-select>
    <el-select v-model="e.assetId" filterable placeholder="选择证书" style="width: 420px">
      <el-option
        v-for="c in certs.filter((c) => e.role !== 'PRIVATE' || (c.owner === 'OWN' && c.hasKey))"
        :key="c.id"
        :value="c.id"
        :label="label(c)"
      />
    </el-select>
    <el-input
      v-if="s.type === 'KEYSTORE'"
      v-model="e.alias"
      placeholder="条目别名"
      style="width: 150px"
    />
    <span v-else style="margin: 0 8px; color: var(--el-text-color-secondary)">
      {{ e.alias ? `已有别名：${e.alias}` : '别名自动生成（CN + SHA-256 指纹）' }}
    </span>
    <el-checkbox v-model="e.includeChain">包含证书链</el-checkbox>
    <el-button text @click="s.config.entries.splice(j, 1)">移除</el-button>
  </div>
  <el-button @click="addEntry">添加证书条目</el-button>
</template>
