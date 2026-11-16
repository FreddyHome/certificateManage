# certificateManage 部署与维护

运行要求：Java 21；Windows使用系统自带Windows PowerShell，Linux使用Bash 4.4+、curl及flock（util-linux）。无需安装Node或Maven。

```text
certificateManage/
  lib/certificate-manage.jar    # 可执行JAR，包含前端及第三方依赖
  config/application.yml       # 外置Spring Boot配置
  config/jvm.options           # 每行一个JVM参数，不加外围引号
  bin/start.bat|start.sh
  bin/stop.bat|stop.sh
  bin/restart.bat|restart.sh
  bin/status.bat|status.sh
  bin/service.bat|service.sh    # 共用维护逻辑
  logs/                        # 系统日志与启动输出
  data/                        # 首次启动生成数据库及主密钥
  run/                         # PID和维护锁
```

## 启动与停止

Windows运行`bin\start.bat`、`bin\stop.bat`、`bin\restart.bat`、`bin\status.bat`。Linux运行`./bin/start.sh`等对应脚本；解压工具若未保留执行权限，先执行`chmod +x bin/*.sh`。可从任意工作目录调用，脚本始终以项目根目录运行，避免相对数据库路径漂移。

Java优先取JAVA_HOME，否则使用PATH。可用PORT修改端口（默认8080）；如果直接修改application.yml中的端口或访问地址，请同时设置HEALTH_URL为本机可访问的根地址，例如`http://127.0.0.1:9090`。状态检查会访问首页和`/api/v1/auth/csrf`，不登录、不触发同步任务。START_TIMEOUT默认60秒；Linux STOP_TIMEOUT默认30秒。

启动先检查同一JAR的进程，重复启动不创建第二个实例；PID文件丢失时尝试按精确JAR路径找回。状态返回0表示进程及HTTP检查正常，3表示已停止，1表示进程异常/检查失败。启动超时保留PID方便检查和停止；不会因此误杀其他占用端口的服务。

Linux停止向核对身份的进程发送TERM并等待，超时报告失败，不自动强杀。Windows通过系统进程终止API停止核对身份的进程，不承诺优雅关闭；升级备份必须在确认停止后进行。脚本维护的是独立后台进程，不会注册Windows服务或systemd单元，也不会设置开机启动。

## 配置、日志与数据

`config/application.yml`优先覆盖JAR内默认配置，支持PORT、SERVER_ADDRESS、DB_URL、DB_USERNAME、DB_PASSWORD、ENCRYPTION_KEY_FILE等原环境变量。环境变量仍可覆盖外置配置。默认数据库为`data/certificate-manage.mv.db`，主密钥为`data/master.key`；不要替换或丢失已有主密钥。

系统日志默认`logs/certificate-manage.log`，按10MB滚动、保留30天、总量上限1GB，可通过外置配置调整，LOG_FILE可覆盖文件名。`logs/console.log`及`logs/console-error.log`保存启动输出；Windows每次启动覆盖这两个输出文件，Linux追加，系统滚动日志不受影响。数据库中的审计/执行日志与这些运行日志是不同存储，原审计功能保留。

发布ZIP不含已有数据库、密钥及历史日志。首次启动会按原初始化规则创建数据；升级时先停止旧实例，备份整个data（数据库和密钥必须成对），只替换lib与bin，按需合并config，不用新包覆盖已有data或logs。不要同时启动两个实例操作同一H2文件。

## 源码构建

在源码根目录执行`mvnw.cmd -Pproduction clean verify`（Linux使用`./mvnw`），依次安装/构建前端、运行前后端验证、生成Spring Boot JAR，并在package阶段生成`target/certificate-manage-0.1.0-dist.zip`及同名展开目录。ZIP内根目录固定为certificateManage。verify成功后再交付ZIP；不要把已生成但verify失败的产物当成验收成功。
