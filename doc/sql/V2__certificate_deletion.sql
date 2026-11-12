ALTER TABLE certificate_assets ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_asset_deleted ON certificate_assets(deleted_at);
