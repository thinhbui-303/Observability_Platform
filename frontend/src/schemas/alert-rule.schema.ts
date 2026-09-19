import { z } from "zod";

export const alertRuleSchema = z.object({
  ruleName: z.string().min(1, "Name is required").max(100, "Name must be less than 100 characters"),
  serviceId: z.string().min(1, "Service ID is required"),
  environment: z.string().min(1, "Environment is required"),
  conditionType: z.enum(["ERROR_SPIKE", "PATTERN_MATCH"]),
  thresholdValue: z.number().min(1, "Threshold must be > 0").optional(),
  windowSeconds: z.number().min(1, "Window must be > 0").optional(),
  conditionValue: z.string().optional(),
  severity: z.enum(["CRITICAL", "HIGH", "MEDIUM", "LOW"]),
  notificationChannels: z.array(z.object({
    channelType: z.enum(["WEBHOOK", "SLACK", "TELEGRAM", "EMAIL"]),
    target: z.string().min(1, "Target is required"),
    enabled: z.boolean().default(true),
  })).optional(),
}).superRefine((data, ctx) => {
  if (data.conditionType === "PATTERN_MATCH" && (!data.conditionValue || data.conditionValue.trim() === "")) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "Condition value is required for PATTERN_MATCH",
      path: ["conditionValue"],
    });
  }
  if (data.conditionType === "ERROR_SPIKE" && (!data.thresholdValue || data.thresholdValue <= 0)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "Threshold value is required for ERROR_SPIKE",
      path: ["thresholdValue"],
    });
  }
  if (data.conditionType === "ERROR_SPIKE" && (!data.windowSeconds || data.windowSeconds <= 0)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "Window seconds is required for ERROR_SPIKE",
      path: ["windowSeconds"],
    });
  }
});

export type AlertRuleFormValues = z.infer<typeof alertRuleSchema>;
