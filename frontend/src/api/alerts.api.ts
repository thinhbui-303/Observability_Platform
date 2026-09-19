import { apiClient } from "./client";
import type { AlertResponse, UnifiedResponse } from "@/types/api";

export const alertsApi = {
  list: async (params?: { serviceId?: string; environment?: string; status?: string }) => {
    const { data } = await apiClient.get<UnifiedResponse<AlertResponse[]>>("/api/v1/alerts", { params });
    return data;
  },

  acknowledge: async (id: string) => {
    const { data } = await apiClient.patch<UnifiedResponse<AlertResponse>>(`/api/v1/alerts/${id}/acknowledge`);
    return data;
  },

  resolve: async (id: string) => {
    const { data } = await apiClient.patch<UnifiedResponse<AlertResponse>>(`/api/v1/alerts/${id}/resolve`);
    return data;
  },
};
