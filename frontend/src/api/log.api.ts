import { apiClient } from "./client";
import type { LogSearchRequest, UnifiedResponseLogSearchResponseObject } from "@/schemas/log.schema";

export const logApi = {
  searchLogs: async (params: LogSearchRequest): Promise<UnifiedResponseLogSearchResponseObject> => {
    const response = await apiClient.get("/api/v1/logs", { params });
    return response.data;
  },
};
