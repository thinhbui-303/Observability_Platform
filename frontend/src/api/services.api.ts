import { apiClient } from "./client";
import type { ServiceResponse, ServiceCreateResponse, UnifiedResponse } from "@/types/api";
import type { CreateServiceFormValues } from "@/schemas/service.schema";

export const servicesApi = {
  list: async () => {
    const { data } = await apiClient.get<UnifiedResponse<ServiceResponse[]>>("/api/v1/services");
    return data;
  },

  create: async (payload: CreateServiceFormValues) => {
    const { data } = await apiClient.post<UnifiedResponse<ServiceCreateResponse>>("/api/v1/services", payload);
    return data;
  },

  updateStatus: async (id: string, status: string) => {
    const { data } = await apiClient.patch<UnifiedResponse<ServiceResponse>>(`/api/v1/services/${id}/status`, { status });
    return data;
  },
};
