import { useState } from "react";
import { Outlet, NavLink } from "react-router-dom";
import { useAuthStore } from "@/stores/auth-store";
import { useRoles } from "@/hooks/useRoles";
import { Button } from "@/components/ui/button";
import { LogOut, Activity, Hexagon, Menu, X } from "lucide-react";

export const AppLayout = () => {
  const logout = useAuthStore((state) => state.logout);
  const roles = useRoles();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  return (
    <div className="min-h-screen flex flex-col relative text-white bg-transparent">
      {/* Decorative anime-style background effects */}
      <div className="absolute inset-0 opacity-20 pointer-events-none z-[-1]" style={{ backgroundImage: 'radial-gradient(circle at 10% 20%, rgba(255, 216, 0, 0.1) 0%, transparent 20%), radial-gradient(circle at 90% 80%, rgba(255, 216, 0, 0.05) 0%, transparent 20%)' }}></div>
      <div className="absolute inset-0 pointer-events-none z-[-2] bg-gradient-to-br from-[#1b2520] via-[#0d1410] to-[#0a100b]"></div>

      <header className="h-16 flex items-center justify-between px-6 glass-panel z-50 rounded-b-xl mx-4 mt-2">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" className="md:hidden text-[#FFD800]" onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}>
            {isMobileMenuOpen ? <X className="h-6 w-6" /> : <Menu className="h-6 w-6" />}
          </Button>
          <div className="relative flex items-center justify-center">
            <div className="absolute inset-0 bg-[#FFD800] blur-md opacity-50 rounded-full"></div>
            <Hexagon className="h-7 w-7 text-[#FFD800] relative z-10" />
            <Activity className="h-4 w-4 text-black absolute z-20" />
          </div>
          <h1 className="text-xl font-bold tracking-widest uppercase text-glow text-white hidden sm:block">Obsrv.</h1>
        </div>
        <Button variant="ghost" size="sm" onClick={() => logout()} className="text-[#FFD800] hover:bg-[#FFD800]/10 hover:text-[#FFD800] uppercase tracking-widest text-xs font-bold border border-transparent hover:border-[#FFD800]/50 rounded-full">
          <LogOut className="h-4 w-4 mr-2" />
          Disconnect
        </Button>
      </header>
      
      <div className="flex flex-1 overflow-hidden p-4 gap-4 relative">
        {/* Sidebar Overlay for Mobile */}
        {isMobileMenuOpen && (
          <div className="fixed inset-0 bg-black/60 z-30 md:hidden" onClick={() => setIsMobileMenuOpen(false)}></div>
        )}

        {/* Navigation Sidebar */}
        <aside className={`fixed md:relative z-40 w-64 h-[calc(100vh-100px)] flex flex-col glass-panel rounded-2xl p-4 transition-transform duration-300 ease-in-out bg-[#0a100b]/90 backdrop-blur-xl md:translate-x-0 ${isMobileMenuOpen ? 'translate-x-0' : '-translate-x-[120%]'}`}>
          
          <nav className="space-y-2 mt-4 relative z-10 flex-1">
            <NavLink
              to="/app/dashboard"
              onClick={() => setIsMobileMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                  isActive
                    ? "bg-[#FFD800] text-black shadow-[0_0_15px_rgba(255,216,0,0.4)]"
                    : "text-gray-400 hover:bg-[#FFD800]/10 hover:text-[#FFD800]"
                }`
              }
            >
              Dashboard
            </NavLink>
            <NavLink
              to="/app/logs"
              onClick={() => setIsMobileMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                  isActive
                    ? "bg-[#FFD800] text-black shadow-[0_0_15px_rgba(255,216,0,0.4)]"
                    : "text-gray-400 hover:bg-[#FFD800]/10 hover:text-[#FFD800]"
                }`
              }
            >
              Logs Explorer
            </NavLink>
            <NavLink
              to="/app/alerts"
              onClick={() => setIsMobileMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                  isActive
                    ? "bg-[#FFD800] text-black shadow-[0_0_15px_rgba(255,216,0,0.4)]"
                    : "text-gray-400 hover:bg-[#FFD800]/10 hover:text-[#FFD800]"
                }`
              }
            >
              Alerts
            </NavLink>
            <NavLink
              to="/app/alert-rules"
              onClick={() => setIsMobileMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                  isActive
                    ? "bg-[#FFD800] text-black shadow-[0_0_15px_rgba(255,216,0,0.4)]"
                    : "text-gray-400 hover:bg-[#FFD800]/10 hover:text-[#FFD800]"
                }`
              }
            >
              Alert Rules
            </NavLink>
            <NavLink
              to="/app/services"
              onClick={() => setIsMobileMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                  isActive
                    ? "bg-[#FFD800] text-black shadow-[0_0_15px_rgba(255,216,0,0.4)]"
                    : "text-gray-400 hover:bg-[#FFD800]/10 hover:text-[#FFD800]"
                }`
              }
            >
              Services
            </NavLink>
            <NavLink
              to="/app/audit-logs"
              onClick={() => setIsMobileMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                  isActive
                    ? "bg-[#FFD800] text-black shadow-[0_0_15px_rgba(255,216,0,0.4)]"
                    : "text-gray-400 hover:bg-[#FFD800]/10 hover:text-[#FFD800]"
                }`
              }
            >
              Audit Logs
            </NavLink>
            {(roles.includes("ADMIN") || roles.includes("DEVOPS")) && (
              <NavLink
                to="/app/dlq"
                onClick={() => setIsMobileMenuOpen(false)}
                className={({ isActive }) =>
                  `flex items-center px-4 py-3 text-sm font-bold uppercase tracking-wider rounded-xl transition-all duration-300 ${
                    isActive
                      ? "bg-red-500 text-white shadow-[0_0_15px_rgba(239,68,68,0.4)]"
                      : "text-red-400 hover:bg-red-500/10 hover:text-red-500"
                  }`
                }
              >
                Dead Letter Queue
              </NavLink>
            )}
          </nav>

          <div className="mt-4 rounded-xl overflow-hidden relative group">
            <div className="absolute inset-0 bg-gradient-to-t from-black/80 to-transparent z-10"></div>
            <img 
              src="/anime_hacker.png" 
              alt="Cyberpunk Hacker" 
              className="w-full h-40 object-cover opacity-60 group-hover:opacity-100 group-hover:scale-105 transition-all duration-500"
            />
            <div className="absolute bottom-2 left-2 z-20 text-[10px] uppercase font-bold tracking-widest text-[#FFD800]">System Secured</div>
          </div>
        </aside>
        
        <main className="flex-1 w-full overflow-hidden flex flex-col glass-panel rounded-2xl relative">
          <div className="absolute inset-0 p-4 md:p-6 overflow-auto">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
};
