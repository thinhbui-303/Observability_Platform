import { useAuthStore } from "@/stores/auth-store";
import { jwtDecode } from "jwt-decode";

interface JwtPayload {
  sub: string;
  roles: string[];
  iat: number;
  exp: number;
}

export const useRoles = () => {
  const accessToken = useAuthStore((state) => state.accessToken);
  if (!accessToken) return [];
  try {
    const decoded = jwtDecode<JwtPayload>(accessToken);
    return decoded.roles || [];
  } catch {
    return [];
  }
};
