// Typed wrappers for the auth surface.
//
// Mirrors the controllers under
// `backend/src/main/java/com/finsight/api/AuthController.java`
// and `CsrfService.java`. The schemas come from the
// `/v3/api-docs` runtime; the typed client is a thin facade that
// adds deduplication via `once()` and surfaces `ApiError`.

import { type ApiClient, once } from './index';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface AuthSession {
  token: string;
  user: {
    id: string;
    email: string;
    status: string;
    createdAt: string;
  };
  expiresAt: string;
}

export interface CurrentUser {
  authenticated: boolean;
  user: AuthSession['user'] | null;
  csrfToken: string;
}

export function login(
  client: ApiClient,
  body: LoginRequest
): Promise<AuthSession> {
  return once('auth:login', () =>
    client.POST('/api/auth/login', { body }).then((res) => {
      if (res.error || !res.data) {
        throw new Error('login failed');
      }
      return res.data as unknown as AuthSession;
    })
  );
}

export function logout(client: ApiClient): Promise<void> {
  return once('auth:logout', () =>
    client.POST('/api/auth/logout', {}).then(() => undefined)
  );
}

export function currentSession(client: ApiClient): Promise<CurrentUser> {
  return client.GET('/api/auth/session', {}).then((res) => {
    if (res.error || !res.data) {
      return { authenticated: false, user: null, csrfToken: '' };
    }
    return res.data as unknown as CurrentUser;
  });
}

export function register(
  client: ApiClient,
  body: { email: string; password: string; verificationCode: string }
): Promise<AuthSession> {
  return once('auth:register', () =>
    client.POST('/api/auth/register', { body }).then((res) => {
      if (res.error || !res.data) {
        throw new Error('register failed');
      }
      return res.data as unknown as AuthSession;
    })
  );
}

export function issueVerificationCode(
  client: ApiClient,
  email: string
): Promise<{ devCode: string | null; expiresInSeconds: number }> {
  return once(`auth:verify:${email}`, () =>
    client.POST('/api/auth/verify', { body: { email } }).then((res) => {
      if (res.error || !res.data) {
        throw new Error('issue verification code failed');
      }
      return res.data as unknown as { devCode: string | null; expiresInSeconds: number };
    })
  );
}
