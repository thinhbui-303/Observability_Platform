import { useQuery } from "@tanstack/react-query";
import { auditLogsApi } from "@/api/audit-logs.api";

export const useAuditLogs = (action?: string, username?: string, resourceTarget?: string) => {
  return useQuery({
    queryKey: ["audit-logs", { action, username, resourceTarget }],
    queryFn: () => auditLogsApi.list({ action, username, resourceTarget }),
  });
};
