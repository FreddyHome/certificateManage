-- Nullable delivery keys preserve old rows and make retry-after-unknown-commit idempotent.
ALTER TABLE execution_logs ADD COLUMN delivery_key VARCHAR(36);
CREATE UNIQUE INDEX uq_execution_delivery ON execution_logs(delivery_key);
CREATE INDEX ix_exec_run_time ON execution_logs(run_id,occurred_at,id);
CREATE INDEX ix_exec_source_result_time ON execution_logs(source,outcome,occurred_at,id);
CREATE INDEX ix_exec_result_time ON execution_logs(outcome,occurred_at,id);
CREATE INDEX ix_sync_task_latest ON sync_runs(task_id,id);
CREATE INDEX ix_sync_due_time ON sync_tasks(schedule_enabled,deleted_at,next_fire_at,id);
