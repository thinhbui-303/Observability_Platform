import axios, { AxiosError } from 'axios';
import { useAuthStore } from '@/stores/auth-store';

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request Interceptor
apiClient.interceptors.request.use(
  (config) => {
    // We can read directly from the store state
    const token = useAuthStore.getState().accessToken;
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response Interceptor
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  (error: AxiosError) => {
    if (error.response) {
      // 401 Unauthorized
      if (error.response.status === 401) {
        useAuthStore.getState().logout();
        // The router should listen to the state change or we can do a hard redirect:
        // window.location.href = '/login'; 
        // But Zustand state change will trigger ProtectedRoute to redirect.
      }
      
      // 403 Forbidden
      if (error.response.status === 403) {
        // Typically we don't redirect here, just maybe show a toast
        console.error("Access Denied (403)");
      }
    }
    return Promise.reject(error);
  }
);
