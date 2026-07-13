import http, { unwrap } from "./http";

export interface LoginPayload {
  username: string;
  password: string;
}

export interface LoginResult {
  token: string;
  username: string;
  displayName: string;
  role: string;
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return unwrap<LoginResult>(http.post("/auth/login", payload));
}
