import { API_URL } from './config';

export type TransactionStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'PENDING_VERIFICATION'
  | 'COMPLETED'
  | 'FAILED'
  | 'COMPENSATING'
  | 'FLAGGED';

export interface User {
  id: string;
  fullName: string;
  email: string;
  phone: string;
  accountNumber: string;
}

export interface AuthResponse {
  accessToken: string;
  expiresInSeconds: number;
  user: User;
}

export interface Account {
  accountNumber: string;
  iban: string;
  accountHolderName: string;
  accountType: 'SAVINGS' | 'CURRENT' | 'FIXED_DEPOSIT';
  status: 'ACTIVE' | 'BLOCKED' | 'CLOSED';
  balance: number;
}

export interface Recipient {
  iban: string;
  displayName: string;
  canReceive: boolean;
}

export interface Transaction {
  id: string;
  senderAccountNumber: string;
  receiverAccountNumber: string;
  receiverIban: string | null;
  amount: number;
  status: TransactionStatus;
  description: string | null;
  failureReason: string | null;
  createdAt: string | null;
  verificationSecondsRemaining: number | null;
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function request<T>(method: string, path: string, token: string | null, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_URL}/api/v1${path}`, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(`Can't reach Retech Bank at ${API_URL}. Check that your phone and PC are on the same Wi-Fi.`, 0);
  }

  const text = await response.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }

  if (!response.ok) {
    const serverMessage =
      data && typeof data === 'object' && 'error' in data ? String((data as { error: unknown }).error) : '';
    const fallback =
      response.status === 401
        ? 'Your session has ended. Log in again.'
        : response.status === 429
          ? 'Too many attempts. Wait a moment and try again.'
          : `Something went wrong (${response.status}).`;
    throw new ApiError(serverMessage || fallback, response.status);
  }
  return data as T;
}

const id = encodeURIComponent;

export const auth = {
  register: (input: { fullName: string; email: string; phone: string; password: string; initialDeposit: number }) =>
    request<AuthResponse>('POST', '/auth/register', null, input),
  login: (email: string, password: string) => request<AuthResponse>('POST', '/auth/login', null, { email, password }),
};

/** Calls that need a logged-in user. */
export function bankApi(token: string) {
  return {
    account: (accountNumber: string) => request<Account>('GET', `/accounts/${id(accountNumber)}`, token),
    lookupRecipient: (iban: string) => request<Recipient>('GET', `/accounts/iban/${id(iban)}`, token),
    history: (accountNumber: string) => request<Transaction[]>('GET', `/transactions/account/${id(accountNumber)}`, token),
    transfer: (input: { senderAccountNumber: string; receiverIban: string; amount: number; description: string }) =>
      request<Transaction>('POST', '/transactions/transfer', token, input),
    transaction: (transactionId: string) => request<Transaction>('GET', `/transactions/${id(transactionId)}`, token),
    verify: (transactionId: string, otp: string) =>
      request<Transaction>('POST', `/transactions/${id(transactionId)}/verify?otp=${id(otp)}`, token),
    reportFraud: (transactionId: string) =>
      request<Transaction>('POST', `/transactions/${id(transactionId)}/report-fraud`, token),
  };
}

export type BankApi = ReturnType<typeof bankApi>;
