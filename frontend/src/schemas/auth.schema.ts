import { z } from "zod";

export const LoginSchema = z.object({
  username: z.string().min(1, { message: "Username is required" }),
  password: z.string().min(1, { message: "Password is required" }),
});

export type LoginRequest = z.infer<typeof LoginSchema>;

export interface JwtResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface UnifiedResponseJwtResponse {
  code: string;
  data: JwtResponse;
  message?: string;
  timestamp?: string;
  path?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
}
