<script setup lang="ts">
import RequestParameters from '../../components/RequestParameters.vue';
import type {
  ParameterMaps,
  SyncCertificate,
  SyncServer,
  SyncTask,
  TransferStep,
} from '../../services/sync-types';
import {
  certificateLabel as label,
  producesFile,
  outputFilename,
  parameterFields,
  parameterLabels,
} from './sync-editor';

const props = defineProps<{
  s: TransferStep;
  i: number;
  form: SyncTask;
  servers: SyncServer[];
  certs: SyncCertificate[];
  maps: ParameterMaps;
}>();

function selectSource() {
  props.s.config.certificateId = null;
  const source = props.form.steps.find((step) => step.key === props.s.config.sourceStep);
  if (source) props.s.config.filename = outputFilename(source) || props.s.config.filename;
}

function changeOperation() {
  props.s.config.sourceStep = '';
  props.s.config.certificateId = null;
}

function changeMethod() {
  if (['GET', 'HEAD'].includes(props.s.config.method)) props.s.config.bodyMode = 'NONE';
}
</script>

<template>
  <el-form-item label="服务器">
    <el-select v-model="s.config.serverId" filterable>
      <el-option
        v-for="v in servers.filter((v) => v.type === s.type && v.enabled)"
        :key="v.id"
        :value="v.id"
        :label="v.alias"
      />
    </el-select>
  </el-form-item>
  <template v-if="s.type === 'SFTP'">
    <el-form-item label="SFTP操作">
      <el-select v-model="s.config.operation" placeholder="上传文件" @change="changeOperation">
        <el-option label="上传文件" value="UPLOAD" />
        <el-option label="读取文件" value="READ" />
        <el-option label="移动 / 重命名（mv）" value="MOVE" />
      </el-select>
    </el-form-item>
  </template>
  <template v-if="s.type === 'HTTP' || !['MOVE', 'READ'].includes(s.config.operation || 'UPLOAD')">
    <el-form-item label="文件来源步骤（清空可直接导出证书）">
      <el-select v-model="s.config.sourceStep" clearable @change="selectSource">
        <el-option
          v-for="p in form.steps.slice(0, i).filter(producesFile)"
          :key="p.key"
          :value="p.key"
          :label="p.name"
        />
      </el-select>
    </el-form-item>
    <template v-if="!s.config.sourceStep">
      <el-select v-model="s.config.certificateId" clearable filterable placeholder="直接导出证书">
        <el-option v-for="c in certs" :key="c.id" :value="c.id" :label="label(c)" />
      </el-select>
      <el-select v-model="s.config.exportFormat">
        <el-option v-for="f in ['PEM', 'CHAIN_PEM', 'DER', 'P7B']" :key="f" :value="f" />
      </el-select>
    </template>
    <el-form-item label="文件名">
      <el-input v-model="s.config.filename" />
    </el-form-item>
  </template>
  <template v-if="s.type === 'SFTP' && s.config.operation === 'READ'">
    <el-form-item label="远端文件完整路径">
      <el-input v-model="s.config.sourcePath" placeholder="/path/server.p12" />
    </el-form-item>
    <p>输出文件：{{ s.config.sourcePath?.split('/').pop() || '填写路径后自动取原文件名' }}。</p>
    <p>
      后续 SFTP 上传或通用 HTTP 可通过“文件来源步骤”选择本步骤。读取保留源文件，单文件上限 10 MB。
    </p>
  </template>
  <template v-if="s.type === 'SFTP' && s.config.operation === 'MOVE'">
    <el-form-item label="源文件路径">
      <el-input v-model="s.config.sourcePath" placeholder="/path/old.p12" />
    </el-form-item>
    <el-form-item label="目标文件路径">
      <el-input v-model="s.config.targetPath" placeholder="/path/new.p12" />
    </el-form-item>
    <el-checkbox v-model="s.config.appendTimestamp">在完整文件名后追加时间戳</el-checkbox>
    <p v-if="s.config.appendTimestamp">
      执行时按北京时间追加 _yyyyMMddHHmmss，例如 new.p12_20261112153045。
    </p>
  </template>
  <template v-if="s.type === 'SFTP'">
    <el-form-item
      v-if="!['MOVE', 'READ'].includes(s.config.operation || 'UPLOAD')"
      label="远程目录"
    >
      <el-input v-model="s.config.remoteDirectory" />
    </el-form-item>
    <el-checkbox v-if="s.config.operation !== 'READ'" v-model="s.config.overwrite"
      >覆盖已有文件</el-checkbox
    >
  </template>
  <template v-else>
    <el-form-item label="请求路径">
      <el-input v-model="s.config.path" />
    </el-form-item>
    <el-select v-model="s.config.method" placeholder="Method" @change="changeMethod">
      <el-option
        v-for="m in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']"
        :key="m"
        :value="m"
      />
    </el-select>
    <el-select
      v-if="!['GET', 'HEAD'].includes(s.config.method)"
      v-model="s.config.bodyMode"
      placeholder="请求体类型"
      :disabled="['GET', 'HEAD'].includes(s.config.method)"
    >
      <el-option
        v-for="m in ['NONE', 'JSON', 'FORM', 'MULTIPART', 'BINARY', 'TEXT']"
        :key="m"
        :value="m"
      />
    </el-select>
    <el-form-item v-if="s.config.bodyMode === 'MULTIPART'" label="文件字段名">
      <el-input v-model="s.config.fileField" />
    </el-form-item>
    <el-form-item
      v-for="f in parameterFields"
      :key="f"
      :label="parameterLabels[f]"
      v-show="f !== 'fields' || ['FORM', 'MULTIPART'].includes(s.config.bodyMode)"
    >
      <RequestParameters
        :model-value="JSON.parse(maps[s.key + f] || '{}')"
        @update:model-value="maps[s.key + f] = JSON.stringify($event)"
      />
    </el-form-item>
    <el-form-item
      v-if="['JSON', 'TEXT'].includes(s.config.bodyMode)"
      label="请求正文（支持 ${fileBase64} 和 ${certificateBase64}）"
    >
      <el-input v-model="s.config.body" type="textarea" :rows="4" />
    </el-form-item>
    <el-form-item label="成功 HTTP 状态码">
      <el-select v-model="s.config.successCodes" multiple filterable allow-create>
        <el-option v-for="c in [200, 201, 202, 204]" :key="c" :value="c" :label="String(c)" />
      </el-select>
    </el-form-item>
    <el-form-item label="响应 JSONPath">
      <el-input v-model="s.config.jsonPath" />
    </el-form-item>
    <el-form-item label="预期值">
      <el-input v-model="s.config.expectedValue" />
    </el-form-item>
  </template>
</template>
