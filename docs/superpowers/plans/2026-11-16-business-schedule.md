# 业务执行计划实施计划

**Goal:** 以业务表单配置证书同步执行计划，准确预览并兼容旧表达式。
**Spec:** doc/schedule-business-design.md（用户已要求执行）。
**Architecture:** SchedulePlan统一归一化、兼容解析、摘要及时间计算；保存结构化JSON并同时派生cron/timezone。前端独立ScheduleEditor组件只调用预览，不保存任务。
**Tech Stack:** Spring Boot / Spring CronExpression / H2 Flyway / Vue3 / Element Plus。

## Review Focus

- 复杂旧规则编辑其他字段不可被重写；仅能无损识别的规则转换为业务表单。
- 预览不依赖步骤完整性、不写任务；错误/旧请求返回不能覆盖当前预览。
- 月末、闰年、跨年及非默认时区（含夏令时）采用服务端实际计算。
- 关闭定时保留参数，草稿可保存参数但不能启用。
- 结构化计划是保存来源，不信任前端冗余cron/摘要；旧API请求保留兼容。

## Tasks

- [x] 1. 测试先行：SchedulePlanTest覆盖五种频率、固定时钟预览、无效输入、严格旧规则回显；观察失败后实现SchedulePlan。
- [x] 2. 新增V11 schedule_json及SchedulePreviewController；WorkflowService同事务保存派生字段，WorkflowQueries返回schedule/scheduleSummary。集成测试验证保存/预览/权限/旧API/旧复杂规则保持不变。
- [x] 3. 前端增加计划类型、预览请求、ScheduleEditor与防抖请求状态；替换列表及编辑Cron字段；单元测试覆盖过期响应、显示时间、计划切换和参数保留。
- [x] 4. 前后端全套测试、格式、类型及构建；独立复查并修复实质问题；重新打包重启，保留原data及主密钥，检查首页/API/新资源。

## Execution Notes

本地原有未提交的keyStore换证改动保持原样；在原工作目录实施以沿用运行环境，不创建提交或覆盖用户工作。界面沿用Element Plus现有视觉体系。每月日期首版1–28日或最后一天，分钟间隔固定5/10/15/20/30。高级设置对旧自定义计划保留，常用计划不默认暴露表达式。

验证：新增业务计划单元/保存/API测试先红后绿；迁移测试验证V10复杂规则升级保持不变；前端异步预览状态测试先红后绿。全量生产test及spotless检查成功（logs/schedule-full-backend.log）：后端113项通过。清理target/classes与target/test-classes后重新编译，排除IDE残留错误字节码；原运行JAR及data未受影响。

复查修复：移除校验块中重复的旧计划时间，实时预览作为唯一时间来源；CUSTOM计划预览和列表显示秒，新增秒级触发显示测试先红后绿。最终前端29项测试、格式、类型及生产构建通过。后端在复查后未改动。

部署：停止原进程63148，备份原数据库和主密钥至data/backups/before-schedule-20261116-102514；生产JAR重新打包成功，新进程50224使用原工作目录和data。启动日志显示V10→V11迁移成功；主密钥SHA-256与重启前一致。首页200、CSRF200、未认证API401、新同步资源200且包含业务计划文案（logs/schedule-smoke.json）。

浏览器验收：实际登录、列表中文执行计划、新任务默认手动及草稿限制、每天02:00预览、每周一/周五多选预览均正常；预览返回2026-11-20/23/27 02:00，与星期及北京时间一致。已查看实际布局并关闭未保存的测试编辑框，没有新建或修改业务任务。
