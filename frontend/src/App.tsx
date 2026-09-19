import {
  createBrowserRouter,
  RouterProvider,
  Navigate,
} from "react-router-dom";
import { LoginPage } from "./pages/LoginPage";
import { AppLayout } from "./components/layout/AppLayout";
import { ProtectedRoute } from "./routes/ProtectedRoute";
import { PublicRoute } from "./routes/PublicRoute";
import { LogsPage } from "./pages/LogsPage";
import { TracePage } from "./pages/TracePage";
import { DashboardPage } from "./pages/DashboardPage";
import { AlertsPage } from "./pages/AlertsPage";
import { AlertRulesPage } from "./pages/AlertRulesPage";
import { ServicesPage } from "./pages/ServicesPage";
import { AuditLogsPage } from "./pages/AuditLogsPage";
import { DlqPage } from "./pages/DlqPage";
import { RoleGuard } from "./routes/RoleGuard";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const queryClient = new QueryClient();

const router = createBrowserRouter([
  {
    path: "/",
    // The root path redirects to /login if unauthenticated, and /login redirects to /app/logs if authenticated
    element: <Navigate to="/login" replace />,
  },
  {
    path: "/",
    element: <PublicRoute />,
    children: [
      {
        path: "login",
        element: <LoginPage />,
      },
    ],
  },
  {
    path: "/app",
    element: <ProtectedRoute />,
    children: [
      {
        path: "",
        element: <AppLayout />,
        children: [
          {
            index: true,
            element: <Navigate to="logs" replace />,
          },
          {
            path: "logs",
            element: <LogsPage />,
          },
          {
            path: "traces/:traceId",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS", "DEVELOPER"]}>
                <TracePage />
              </RoleGuard>
            ),
          },
          {
            path: "dashboard",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS", "DEVELOPER", "VIEWER"]}>
                <DashboardPage />
              </RoleGuard>
            ),
          },
          {
            path: "alerts",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS", "DEVELOPER", "VIEWER"]}>
                <AlertsPage />
              </RoleGuard>
            ),
          },
          {
            path: "alert-rules",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS", "DEVELOPER", "VIEWER"]}>
                <AlertRulesPage />
              </RoleGuard>
            ),
          },
          {
            path: "services",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS", "DEVELOPER", "VIEWER"]}>
                <ServicesPage />
              </RoleGuard>
            ),
          },
          {
            path: "audit-logs",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS"]}>
                <AuditLogsPage />
              </RoleGuard>
            ),
          },
          {
            path: "dlq",
            element: (
              <RoleGuard roles={["ADMIN", "DEVOPS"]}>
                <DlqPage />
              </RoleGuard>
            ),
          },
        ],
      },
    ],
  },
]);

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}

export default App;
