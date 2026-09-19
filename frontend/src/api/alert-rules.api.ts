import { apiClient } from "./client";
import type { AlertRuleResponse, UnifiedResponse } from "@/types/api";
import type { AlertRuleFormValues } from "@/schemas/alert-rule.schema";

export const alertRulesApi = {
  list: async (params?: { service?: string; environment?: string }) => {
    const { data } = await apiClient.get<UnifiedResponse<AlertRuleResponse[]>>("/api/v1/alert-rules", { params });
    return data;
  },

  create: async (payload: AlertRuleFormValues) => {
    const { data } = await apiClient.post<UnifiedResponse<AlertRuleResponse>>("/api/v1/alert-rules", payload);
    return data;
  },

  update: async (id: number, payload: AlertRuleFormValues) => {
    const { data } = await apiClient.put<UnifiedResponse<AlertRuleResponse>>(`/api/v1/alert-rules/${id}`, payload);
    return data;
  },

  setEnabled: async (id: number, enabled: boolean) => {
    const { data } = await apiClient.patch<UnifiedResponse<AlertRuleResponse>>(`/api/v1/alert-rules/${id}/enabled`, { enabled });
    return data;
  },

  delete: async (id: number) => {
    const { data } = await apiClient.delete<UnifiedResponse<void>>(`/api/v1/alert-rules/${id}`);
    return data;
  },
};
