export const TOKEN_KEY = "ruikang-admin-token";

export function clearLocalSession() {
  localStorage.removeItem(TOKEN_KEY);
}
