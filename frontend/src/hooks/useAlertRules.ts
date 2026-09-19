import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { alertRulesApi } from "@/api/alert-rules.api";
import type { AlertRuleFormValues } from "@/schemas/alert-rule.schema";
import { toast } from "sonner";

export const useAlertRules = (service?: string, environment?: string) => {
  return useQuery({
    queryKey: ["alert-rules", { service, environment }],
    queryFn: () => alertRulesApi.list({ service, environment }),
  });
};

export const useCreateAlertRule = (onSuccessCallback?: () => void) => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: (payload: AlertRuleFormValues) => alertRulesApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["alert-rules"] });
      toast.success("Alert rule created successfully!");
      if (onSuccessCallback) onSuccessCallback();
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to create alert rule");
    }
  });
};

export const useUpdateAlertRule = (onSuccessCallback?: () => void) => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: ({ id, payload }: { id: number; payload: AlertRuleFormValues }) => alertRulesApi.update(id, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["alert-rules"] });
      toast.success("Alert rule updated successfully!");
      if (onSuccessCallback) onSuccessCallback();
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to update alert rule");
    }
  });
};

export const useToggleAlertRule = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => alertRulesApi.setEnabled(id, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["alert-rules"] });
      toast.success("Alert rule status updated!");
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to update alert rule status");
    }
  });
};

export const useDeleteAlertRule = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: (id: number) => alertRulesApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["alert-rules"] });
      toast.success("Alert rule deleted successfully!");
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to delete alert rule");
    }
  });
};
