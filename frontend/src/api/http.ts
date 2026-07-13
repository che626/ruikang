import axios from "axios";
import { clearLocalSession } from "@/auth/session";

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
}

const http = axios.create({
  baseURL: "/api",
  timeout: 10000,
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message;
    if (typeof message === "string" && message) {
      error.message = message;
    }
    if (error?.response?.status === 401 && window.location.pathname !== "/login") {
      clearLocalSession();
      const redirect = `${window.location.pathname}${window.location.search}`;
      window.location.assign(`/login?redirect=${encodeURIComponent(redirect)}`);
    }
    return Promise.reject(error);
  },
);

export async function unwrap<T>(promise: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  const response = await promise;
  if (!response.data.success) {
    throw new Error(response.data.message);
  }
  return response.data.data;
}

export default http;
