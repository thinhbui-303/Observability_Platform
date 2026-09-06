package com.thinhbui303.observability.common;

import java.util.List;

public final class AlertColumnContract {

    private AlertColumnContract() {
    }

    // Single source of truth for V4__create_alerts.sql. PG data_type strings are the exact
    // values returned by information_schema.columns.data_type.
    public static final List<AlertColumn> ALERTS = List.of(
            new AlertColumn("id", "character varying", false),
            new AlertColumn("rule_id", "bigint", true),
            new AlertColumn("service_id", "character varying", true),
            new AlertColumn("environment", "character varying", false),
            new AlertColumn("window_start", "timestamp with time zone", false),
            new AlertColumn("severity", "character varying", false),
            new AlertColumn("status", "character varying", false),
            new AlertColumn("triggered_at", "timestamp with time zone", false),
            new AlertColumn("acknowledged_at", "timestamp with time zone", true),
            new AlertColumn("resolved_at", "timestamp with time zone", true),
            new AlertColumn("occurrence_count", "integer", false)
    );
}