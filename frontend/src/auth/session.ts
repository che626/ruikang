export const TOKEN_KEY = "ruikang-admin-token";
export const SESSION_MARKER = "authenticated";

export function clearLocalSession() {
  localStorage.removeItem(TOKEN_KEY);
}
