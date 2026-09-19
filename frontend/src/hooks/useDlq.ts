import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { dlqApi, type DlqProcessRequest } from "@/api/dlq.api";

export const useDlqMessages = (limit: number = 20) => {
  return useQuery({
    queryKey: ["dlq", limit],
    queryFn: () => dlqApi.list(limit),
  });
};

export const useProcessDlq = () => {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: (payload: DlqProcessRequest) => dlqApi.process(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["dlq"] });
    },
  });
};
