import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { alertsApi } from "@/api/alerts.api";
import { toast } from "sonner";

export const useAlerts = (serviceId?: string, environment?: string, status?: string) => {
  return useQuery({
    queryKey: ["alerts", { serviceId, environment, status }],
    queryFn: () => alertsApi.list({ serviceId, environment, status }),
  });
};

export const useAcknowledgeAlert = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: (id: string) => alertsApi.acknowledge(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["alerts"] });
      toast.success("Alert acknowledged successfully!");
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to acknowledge alert");
    }
  });
};

export const useResolveAlert = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: (id: string) => alertsApi.resolve(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["alerts"] });
      toast.success("Alert resolved successfully!");
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to resolve alert");
    }
  });
};
