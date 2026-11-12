# Server and certificate sync implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independent server/frontend tasks; root implements tightly coupled workflow and performs integration. Task reviews and whole-change review required. No Git repository exists: preserve current workspace, no commits/worktrees.

**Goal:** Configurable SFTP/HTTP servers and ordered certificate synchronization workflows with JKS/PKCS12, scheduled and manual runs.
**Architecture:** Existing Spring Boot/Vue/H2 single JAR. Immutable encrypted server/config snapshots, SQL reference relations, bounded background execution.
**Spec:** `doc/server-sync-design.md`, approved 2026-11-09.

## Global constraints
- Existing administrator session and CSRF; encrypted secrets never returned or logged.
- SFTP pinned host fingerprint; HTTPS verified; no redirect; configured server origins only.
- Fixed ordered components, stop on failure, no auto retry; active task mutual exclusion and persisted runs.
- Customer public keys only, roots export no descendants. JKS and PKCS12 both supported.
- Certificate/server references protect deletion, including customer chain materials and active run snapshots.

## Review focus
- Editing task/credentials after enqueue cannot change existing run contents.
- Deleted/expired/missing-key certificates cannot execute.
- Aliases are unique and server renaming cannot break stable IDs.
- Timeout may have committed remote changes; never automatically replay after crash.
- Store/file/request content must not leak through logs or run responses.

## Shared API contract
- Task JSON: `id,name,description,revision,state(DRAFT/READY),scheduleEnabled,cron,timezone,steps[]`.
- Step JSON: `key,name,type(KEYSTORE/TRUSTSTORE/SFTP/HTTP),config`.
- Store config: `format(JKS/PKCS12),filename,password,keyPassword,entries[{assetId,alias,role(PRIVATE/TRUST),includeChain}]`. Blank existing passwords retain. `passwordConfigured/keyPasswordConfigured` returned instead of secrets.
- Transfer config: `serverId,sourceStep` OR `certificateId,exportFormat(PEM/CHAIN_PEM/DER/P7B)`, `filename`; SFTP `remoteDirectory,overwrite`; HTTP `path,method,bodyMode(MULTIPART/BINARY/JSON),fileField,fields,headers,query,body,successCodes,jsonPath,expectedValue`.
- Server public JSON: `id,alias,type,description,enabled,revision,config` (config secret fields masked, e.g. passwordConfigured). HTTP `baseUrl,authType(NONE/BASIC/BEARER),username,password,token,headers,secretHeaders,testPath,testMethod,testCodes,trustAssetIds`; SFTP `host,port,username,authType(PASSWORD/KEY),password,privateKey,passphrase,fingerprint`; common timeouts.
- `ConfigSecrets.save(Map<String,Object>) -> Long`, `read(Long)->Map<String,Object>` immutable encrypted bundles.
- `ServerService.snapshot(long id)->Map` contains `id,configId,alias,type`; `connection(long configId)->Map` returns full public+secret connection fields only internally; `enabled(long id)->boolean`.
- `RemoteTransport.execute(long serverId,long configId,Map<String,Object> step,byte[] data,String filename)->Map<String,Object>` sanitized result; data nullable for plain JSON requests.
- Endpoints per design; `/sync-tasks/{id}/execute` returns `{id}`; GET runs returns lowerCamelCase maps.

## Tasks
- [x] 1. Server/config transport. Files `server/*`, `common/ConfigSecrets`, `V3__managed_servers.sql`, pom dependency. Write failing tests, implement masked CRUD/versioning/FKs, HTTP/SFTP verified test+upload, run focused tests. Owned by server implementer.
- [x] 2. Workflow backend. Files `sync/*`, `V4__sync_workflows.sql`, CertificateService deletion hook. Root: failing Store/validation/HTTP execution tests; implement task save/references/secrets, Store builder, executor/cron/records/controllers; verify complete test suite.
- [x] 3. Vue pages. Files `views/servers`, `views/sync`, routes/menu. Owned by frontend implementer. Editable steps, cert selectors, conditional forms, server tests, validation/run history, typecheck and production build.
- [x] 4. Integration review. Fresh review for server/frontend tasks plus whole project. Correct findings, local HTTP/SFTP fixture tests, production package and existing H2 migration/startup; update docs/README and handoff.

## Rulings / ledger
- Implementation remains in current no-Git workspace. No external server credentials provided: automated local fixtures verify transfers; no contact with production endpoints.
- Default task draft, scheduling off, six-field Cron `0 0 2 * * *`, Asia/Shanghai. Manual execution requires READY.
- Ruling: artifacts remain in bounded memory, not temporary directories — removes temporary private-data cleanup failure modes; limits are 10MB per Store and25MB generated total per run.
- Ruling: preserve historical encrypted secret bundles — stable run references with no unsafe key deletion; storage cleanup deferred explicitly.
- Ruling: SFTP fail-if-exists uses standard protocol rename after existence check; overwrite uses atomic+overwrite — Atomic alone implies overwrite on some servers and fixture proved data loss risk. No remote delete fallback.
- Validation evidence so far: complete backend suite passed24 tests, Vue3 tests and production build passed; task reviews corrected timeout units, readable result fields, HTTP/SFTP deadlines and no-overwrite behavior. Final review ongoing.

- Final:24 backend tests,3 frontend tests passed; production build/package passed; existing file H2 V2->V4 migration and JAR startup passed. Backup retained under data/backups. See server-sync-verification.md.
