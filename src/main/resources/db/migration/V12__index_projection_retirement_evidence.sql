-- Retirement by replacement names the departing target through previous_run_id.
-- This index supports its bounded inventory lookup without rewriting V10/V11 history.
CREATE INDEX projection_route_departing_target_history
    ON projection_route_audit(previous_run_id, revision) WHERE action = 'REPLACE';
CREATE INDEX projection_rebuild_scope_page ON projection_rebuild_runs(source_name, topic, run_id);
