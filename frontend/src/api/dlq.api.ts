import { apiClient } from "./client";
import type { UnifiedResponse } from "@/types/api";

export interface DlqMessageDto {
  partition: number;
  offset: number;
  key: string;
  payload: string;
  headers: Record<string, string>;
  errorReason: string;
  firstFailedAt: string;
}

export interface DlqProcessRequest {
  action: "RETRY" | "DISCARD";
  limit: number;
}

export const dlqApi = {
  list: async (limit: number = 20) => {
    const { data } = await apiClient.get<UnifiedResponse<DlqMessageDto[]>>(`/api/v1/dlq?limit=${limit}`);
    return data;
  },

  process: async (payload: DlqProcessRequest) => {
    const { data } = await apiClient.post<UnifiedResponse<string>>("/api/v1/dlq/process", payload);
    return data;
  },
};
