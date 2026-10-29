import type { Transaction } from './api';
import { maskIban } from './iban';

/** Turkish lira in Turkish style: ₺1.234,56 (dot for thousands, comma for decimals). */
export function money(amount: number): string {
  const [whole, cents] = Math.abs(amount).toFixed(2).split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${amount < 0 ? '-' : ''}₺${grouped},${cents}`;
}

/**
 * Reads an amount as people type it in Turkey ("1.234,56", "10,5") or in
 * English style ("1234.56"). Returns NaN when it isn't a number.
 */
export function parseAmount(text: string): number {
  const cleaned = text.replace(/[\s₺]/g, '');
  if (!cleaned) return NaN;
  if (cleaned.includes(',')) {
    // Comma is the decimal separator; dots are thousands separators
    return Number(cleaned.replace(/\./g, '').replace(',', '.'));
  }
  // No comma: dots followed by exactly three digits are thousands ("1.234.567")
  if (/^\d{1,3}(\.\d{3})+$/.test(cleaned)) return Number(cleaned.replace(/\./g, ''));
  return Number(cleaned);
}

export const maskAccount = (n: string) => (n.length > 4 ? `•••• ${n.slice(-4)}` : n);

/** Masked IBAN of a transfer's recipient; older transfers made by account number fall back to that. */
export function recipientLabel(tx: Pick<Transaction, 'receiverIban' | 'receiverAccountNumber'>): string {
  return tx.receiverIban ? maskIban(tx.receiverIban) : maskAccount(tx.receiverAccountNumber);
}

export function maskEmail(email: string): string {
  const at = email.indexOf('@');
  return at <= 1 ? email : `${email[0]}•••${email.slice(at)}`;
}

export function firstName(fullName: string): string {
  return fullName.trim().split(/\s+/)[0] ?? fullName;
}

/** Customer-facing part of a failure reason (drops the internal SAGA audit suffix). */
export function customerReason(reason: string | null): string {
  if (!reason) return '';
  return reason.split(' - SAGA')[0];
}
