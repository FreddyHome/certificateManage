ALTER TABLE execution_logs ADD COLUMN raw_trace_id VARCHAR(36);
CREATE INDEX ix_exec_log_trace ON execution_logs(raw_trace_id,id);
CREATE TABLE execution_log_raw(
 id VARCHAR(36) PRIMARY KEY,
 trace_id VARCHAR(36) NOT NULL,
 step_key VARCHAR(100),
 event_order BIGINT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 kind VARCHAR(60) NOT NULL,
 title VARCHAR(300) NOT NULL,
 payload_bytes BIGINT NOT NULL,
 payload_ciphertext BLOB NOT NULL,
 payload_nonce BINARY(12) NOT NULL
);
CREATE INDEX ix_raw_trace_step ON execution_log_raw(trace_id,step_key,event_order);
