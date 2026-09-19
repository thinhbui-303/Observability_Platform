import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { servicesApi } from "@/api/services.api";
import type { CreateServiceFormValues } from "@/schemas/service.schema";
import { toast } from "sonner";

export const useServices = () => {
  return useQuery({
    queryKey: ["services"],
    queryFn: servicesApi.list,
  });
};

export const useCreateService = (onSuccessCallback: (apiKey: string) => void) => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: (payload: CreateServiceFormValues) => servicesApi.create(payload),
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ["services"] });
      toast.success("Service created successfully!");
      if (response.data?.plaintextApiKey) {
        onSuccessCallback(response.data.plaintextApiKey);
      }
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to create service");
    }
  });
};

export const useUpdateServiceStatus = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => servicesApi.updateStatus(id, status),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["services"] });
      toast.success("Service status updated successfully!");
    },
    onError: (error: any) => {
      toast.error(error.response?.data?.message || "Failed to update service status");
    }
  });
};
