import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import type { LoginRequest } from "@/schemas/auth.schema";
import { LoginSchema } from "@/schemas/auth.schema";
import { authApi } from "@/api/auth.api";
import { useAuthStore } from "@/stores/auth-store";
import { Eye, EyeOff, Loader2, Activity, Cloud, Cpu, Database } from "lucide-react";

export const LoginPage = () => {
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const login = useAuthStore((state) => state.login);

  const form = useForm<LoginRequest>({
    resolver: zodResolver(LoginSchema),
    defaultValues: {
      username: "",
      password: "",
    },
  });

  const onSubmit = async (values: LoginRequest) => {
    setErrorMsg(null);
    try {
      const response = await authApi.login(values);
      if (response.code === "SUCCESS" && response.data) {
        login(response.data.accessToken);
        // Router will redirect
      }
    } catch (error: any) {
      if (error.response?.status === 401) {
        setErrorMsg("Invalid username or password");
      } else {
        setErrorMsg("An unexpected error occurred. Please try again.");
      }
    }
  };

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="flex h-screen w-full items-center justify-center bg-gradient-to-br from-[#1b2520] via-[#0d1410] to-[#0a100b] font-sans">
      {/* Decorative background waves can go here if needed */}
      <div className="absolute inset-0 opacity-20 pointer-events-none" style={{ backgroundImage: 'radial-gradient(circle at 10% 20%, rgba(255, 216, 0, 0.1) 0%, transparent 20%), radial-gradient(circle at 90% 80%, rgba(255, 216, 0, 0.05) 0%, transparent 20%)' }}></div>

      <div className="relative z-10 flex w-[90%] max-w-[1000px] flex-col md:flex-row rounded-3xl bg-[#0f1411] shadow-2xl overflow-hidden border border-[#232f28]">
        
        {/* Top Navigation - Visible on desktop, hidden on very small screens */}
        <div className="absolute top-6 left-8 right-8 flex justify-between items-center z-20 hidden sm:flex">
          <div className="text-white font-bold text-xl tracking-widest flex items-center gap-2">
             Obsrv.
          </div>
          <div className="flex gap-8 text-xs font-semibold text-gray-400 uppercase tracking-widest">
            <a href="#" className="hover:text-[#FFD800] transition-colors">Home</a>
            <a href="#" className="hover:text-[#FFD800] transition-colors">Docs</a>
            <a href="#" className="hover:text-[#FFD800] transition-colors">Contact</a>
          </div>
        </div>

        {/* Left Side: Form */}
        <div className="flex w-full flex-col justify-center px-8 py-12 md:w-1/2 md:px-16 pt-24 pb-16">
          <div className="mb-10">
            <h2 className="text-3xl font-bold text-[#FFD800] mb-2 uppercase tracking-wide">Welcome Back!</h2>
            <p className="text-gray-400 text-sm">
              Don't have an account? <a href="#" className="text-[#FFD800] hover:underline">Sign up</a>
            </p>
          </div>

          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <div className="space-y-2">
              <label className="text-sm font-semibold text-gray-200 tracking-wide" htmlFor="username">
                Username
              </label>
              <div className="relative">
                <input
                  id="username"
                  type="text"
                  placeholder="admin_user"
                  className="w-full rounded-full border-2 border-[#544d21] bg-transparent px-5 py-3 text-white placeholder-gray-600 focus:border-[#FFD800] focus:outline-none focus:ring-1 focus:ring-[#FFD800] transition-all"
                  {...form.register("username")}
                />
              </div>
              {form.formState.errors.username && (
                <p className="text-xs text-red-400 ml-2">{form.formState.errors.username.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <label className="text-sm font-semibold text-gray-200 tracking-wide" htmlFor="password">
                Password
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  placeholder="••••••••"
                  className="w-full rounded-full border-2 border-[#544d21] bg-transparent px-5 py-3 pr-12 text-white placeholder-gray-600 focus:border-[#FFD800] focus:outline-none focus:ring-1 focus:ring-[#FFD800] transition-all"
                  {...form.register("password")}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 hover:text-[#FFD800] focus:outline-none"
                >
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
              {form.formState.errors.password && (
                <p className="text-xs text-red-400 ml-2">{form.formState.errors.password.message}</p>
              )}
            </div>

            {errorMsg && (
              <div className="text-sm text-red-400 font-medium ml-2">{errorMsg}</div>
            )}

            <div className="flex items-center justify-between text-sm px-1">
              <label className="flex items-center gap-2 text-gray-400 cursor-pointer hover:text-gray-300">
                <input type="checkbox" className="form-checkbox h-4 w-4 rounded-sm border-[#544d21] bg-transparent text-[#FFD800] focus:ring-0 focus:ring-offset-0" />
                Remember me
              </label>
              <a href="#" className="text-[#FFD800] hover:underline font-medium">Forget password?</a>
            </div>

            <button
              type="submit"
              disabled={isSubmitting}
              className="mt-6 flex w-full items-center justify-center rounded-full bg-[#FFD800] px-4 py-3.5 font-bold text-black hover:bg-[#e6c200] focus:outline-none focus:ring-2 focus:ring-[#FFD800] focus:ring-offset-2 focus:ring-offset-[#0f1411] disabled:opacity-70 transition-all border border-[#FFD800]"
            >
              {isSubmitting && <Loader2 className="mr-2 h-5 w-5 animate-spin text-black" />}
              {isSubmitting ? "SIGNING IN..." : "Sign In"}
            </button>
          </form>
        </div>

        {/* Right Side: Image and Socials */}
        <div className="relative hidden w-1/2 items-center justify-center bg-[#0f1411] p-8 md:flex border-l border-[#232f28]">
          <div className="relative flex h-full w-full items-center justify-center">
            {/* The circular yellow background behind mascot */}
            <div className="absolute h-72 w-72 rounded-full bg-[#FFD800] opacity-90 blur-sm mix-blend-screen animate-pulse duration-10000"></div>
            <div className="absolute h-[320px] w-[320px] rounded-full bg-[#FFD800]"></div>
            
            <img 
              src="/mascot.png" 
              alt="Observability Mascot" 
              className="relative z-10 w-80 h-80 object-cover rounded-full shadow-lg border-4 border-black/20"
            />
          </div>

          {/* Social Icons Column */}
          <div className="absolute right-6 top-1/2 flex -translate-y-1/2 flex-col gap-4 z-20">
            <a href="#" className="flex h-10 w-10 items-center justify-center rounded-full bg-[#FFD800] text-black hover:bg-[#e6c200] transition-colors shadow-md">
              <Activity size={18} />
            </a>
            <a href="#" className="flex h-10 w-10 items-center justify-center rounded-full bg-[#FFD800] text-black hover:bg-[#e6c200] transition-colors shadow-md">
              <Cloud size={18} />
            </a>
            <a href="#" className="flex h-10 w-10 items-center justify-center rounded-full bg-[#FFD800] text-black hover:bg-[#e6c200] transition-colors shadow-md">
              <Cpu size={18} />
            </a>
            <a href="#" className="flex h-10 w-10 items-center justify-center rounded-full bg-[#FFD800] text-black hover:bg-[#e6c200] transition-colors shadow-md">
              <Database size={18} />
            </a>
          </div>
        </div>
      </div>
    </div>
  );
};
