import { apiClient } from "./client";
import type { AuditLogResponse, UnifiedResponse } from "@/types/api";

export const auditLogsApi = {
  list: async (params?: { action?: string; username?: string; resourceTarget?: string }) => {
    const { data } = await apiClient.get<UnifiedResponse<AuditLogResponse[]>>("/api/v1/audit-logs", { params });
    return data;
  },
};
