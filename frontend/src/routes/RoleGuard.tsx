import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuthStore } from "@/stores/auth-store";
import { jwtDecode } from "jwt-decode";

interface RoleGuardProps {
  roles: string[];
  children: ReactNode;
}

interface JwtPayload {
  sub: string;
  roles: string[];
  iat: number;
  exp: number;
}

export const RoleGuard = ({ roles, children }: RoleGuardProps) => {
  const accessToken = useAuthStore((state) => state.accessToken);

  if (!accessToken) {
    return <Navigate to="/login" replace />;
  }

  try {
    const decoded = jwtDecode<JwtPayload>(accessToken);
    const userRoles = decoded.roles || [];
    
    // Check if user has at least one of the required roles
    const hasRequiredRole = roles.some((role) => userRoles.includes(role));

    if (!hasRequiredRole) {
      return (
        <div className="flex items-center justify-center h-full">
          <div className="text-center space-y-4">
            <h2 className="text-3xl font-bold text-red-500">403 - Forbidden</h2>
            <p className="text-gray-400">You do not have permission to access this page.</p>
          </div>
        </div>
      );
    }

    return <>{children}</>;
  } catch (error) {
    console.error("Failed to decode token", error);
    return <Navigate to="/login" replace />;
  }
};
