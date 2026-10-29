import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ApiError, auth, bankApi, type AuthResponse, type BankApi, type User } from './api';
import * as storage from './storage';

const SESSION_KEY = 'retech.session';

interface Session {
  token: string;
  user: User;
  expiresAt: number;
}

interface AuthState {
  status: 'loading' | 'signedOut' | 'signedIn';
  user: User | null;
  api: BankApi | null;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (input: Parameters<typeof auth.register>[0]) => Promise<void>;
  signOut: () => Promise<void>;
  /** Runs an API call; a 401 (expired or invalid token) signs the user out. */
  guard: <T>(call: Promise<T>) => Promise<T>;
}

const AuthContext = createContext<AuthState | null>(null);

function toSession(response: AuthResponse): Session {
  return {
    token: response.accessToken,
    user: response.user,
    expiresAt: Date.now() + response.expiresInSeconds * 1000,
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);

  // Restore the session saved in the device keychain / keystore
  useEffect(() => {
    storage.getItem(SESSION_KEY)
      .then((raw) => {
        if (!raw) return;
        const saved = JSON.parse(raw) as Session;
        if (saved.expiresAt > Date.now() + 30_000) setSession(saved);
        else storage.deleteItem(SESSION_KEY).catch(() => undefined);
      })
      .catch(() => undefined)
      .finally(() => setLoading(false));
  }, []);

  const save = useCallback(async (next: Session) => {
    setSession(next);
    await storage.setItem(SESSION_KEY, JSON.stringify(next)).catch(() => undefined);
  }, []);

  const signOut = useCallback(async () => {
    setSession(null);
    await storage.deleteItem(SESSION_KEY).catch(() => undefined);
  }, []);

  const signIn = useCallback(
    async (email: string, password: string) => save(toSession(await auth.login(email.trim(), password))),
    [save],
  );

  const signUp = useCallback(
    async (input: Parameters<typeof auth.register>[0]) => save(toSession(await auth.register(input))),
    [save],
  );

  const guard = useCallback(
    async <T,>(call: Promise<T>): Promise<T> => {
      try {
        return await call;
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) await signOut();
        throw e;
      }
    },
    [signOut],
  );

  const value = useMemo<AuthState>(
    () => ({
      status: loading ? 'loading' : session ? 'signedIn' : 'signedOut',
      user: session?.user ?? null,
      api: session ? bankApi(session.token) : null,
      signIn,
      signUp,
      signOut,
      guard,
    }),
    [loading, session, signIn, signUp, signOut, guard],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

/** For screens behind the login guard, where user and api always exist. */
export function useSession() {
  const { user, api, guard, signOut } = useAuth();
  if (!user || !api) throw new Error('useSession used while signed out');
  return { user, api, guard, signOut };
}
