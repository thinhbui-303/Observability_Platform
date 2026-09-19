import { apiClient } from "./client";
import type { LoginRequest, UnifiedResponseJwtResponse } from "@/schemas/auth.schema";

export const authApi = {
  login: async (credentials: LoginRequest): Promise<UnifiedResponseJwtResponse> => {
    const response = await apiClient.post<UnifiedResponseJwtResponse>(
      "/api/v1/auth/login",
      credentials
    );
    return response.data;
  },
};
