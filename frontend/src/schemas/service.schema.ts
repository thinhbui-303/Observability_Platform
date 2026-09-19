import { z } from "zod";

export const createServiceSchema = z.object({
  name: z.string().min(1, "Name is required").max(100, "Name must be less than 100 characters"),
  teamOwner: z.string().min(1, "Team owner is required").max(50, "Team owner must be less than 50 characters"),
  environment: z.string().min(1, "Environment is required").max(50, "Environment must be less than 50 characters"),
});

export type CreateServiceFormValues = z.infer<typeof createServiceSchema>;
