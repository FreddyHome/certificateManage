# 标准部署包与维护脚本

用户要求：发布包根目录为项目文件夹certificateManage；lib存JAR、config存配置、bin存启动/停止/重启/状态脚本，系统日志默认logs。保留单Spring Boot可执行JAR及嵌入前端；运行时固定项目根目录，沿用data和主密钥。支持Windows PowerShell/CMD及Linux Bash，不拆散第三方依赖。

- [x] 先编写发布包及Windows真实进程维护验收脚本，验证缺少发布包时失败。
- [x] production打包阶段生成ZIP及展开目录；外置application.yml/jvm.options；系统日志滚动写logs。
- [x] 实现Windows/Linux服务脚本，处理固定工作目录、PID身份校验、启动存活与首页/API探测、重复启动、停止、重启和状态退出码。
- [x] 完整构建验证、独立复查、干净目录真实脚本验收；更新部署/需求/设计说明。
- [x] 原工作目录安装lib/config/bin，保留现有data和master.key；重新打包重启，检查首页/API与日志。

验证边界：部署包不含已有数据库、密钥、历史日志或源码；Linux脚本至少语法验证，有可用Linux环境再运行真实生命周期测试；不得把仅语法验证描述成已运行。Windows停止通过操作系统终止已核对身份的Java进程，Linux使用TERM等待退出，不默认强杀。

用户后续明确：Windows必须BAT、Linux必须SH。发布bin共5个BAT、5个SH，PowerShell逻辑内嵌service.bat，不另发布PS1或CMD入口；验收用PS1仅保留在源码deploy/tests。

验证记录：完整production test+spotless检查成功（后端116项、前端31项，logs/distribution-full-verify.log）；重新package成功（logs/distribution-package.log）。ZIP约39.5MB，根目录正确，SH为LF且保留可执行权限，BAT为CRLF。Linux仅Git Bash语法检查通过，未运行真实Linux生命周期。

独立复查及修复：空PID文件不能使用会产生AutomationNull的读取结果直接Trim，改为.NET ReadAllText；JVM参数显式UTF8；Windows进程身份兼容带引号的“-jar”参数。新增回归验证空PID、中文参数和重复启动。早期输出重定向的验收实例已单独停止，最终直接调用验收成功，不把早期失败日志当成成功证据。

Windows最终真实验收：target/distribution smoke 7ce2ccc9304245a8bef5e417516d58fc/certificateManage，外置配置端口18080、测试数据库data/smoke及独立主密钥；启动PID51236、重复启动同PID、状态0、重启为60532、停止成功及状态3。验证中文JVM参数保留、根目录日志及数据文件生成；所有测试实例已停止。

原工作目录最终安装并重启：进程61056使用lib/certificate-manage.jar，run/certificate-manage.pid记录PID；首页200、CSRF API200、未认证/me 401，logs/certificate-manage.log正常写入，原data/certificate-manage.mv.db存在，主密钥SHA-256与变更前一致。检查结果见logs/distribution-final-smoke.json。
