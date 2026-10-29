// IBAN helpers (ISO 13616 / ISO 7064 MOD 97-10). The server validates again —
// this only gives instant feedback while typing.

/** Removes spaces and upper-cases: "tr33 0006 ..." → "TR330006...". */
export function normalizeIban(raw: string): string {
  return raw.replace(/\s+/g, '').toUpperCase();
}

export function isValidIban(raw: string): boolean {
  const iban = normalizeIban(raw);
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(iban)) return false;
  if (iban.startsWith('TR') && !/^TR\d{24}$/.test(iban)) return false;
  return mod97(iban.slice(4) + iban.slice(0, 4)) === 1;
}

/** "TR33 0006 1005 1978 6457 8413 26" — groups of four, as printed on cards and statements. */
export function formatIban(raw: string): string {
  return normalizeIban(raw).replace(/(.{4})(?!$)/g, '$1 ');
}

/** "TR33 •••• 1326" — enough to recognise an account without showing all of it. */
export function maskIban(raw: string): string {
  const iban = normalizeIban(raw);
  return iban.length > 8 ? `${iban.slice(0, 4)} •••• ${iban.slice(-4)}` : iban;
}

// Letters count as two digits (A=10 … Z=35); the remainder is computed piecewise
function mod97(value: string): number {
  let remainder = 0;
  for (const ch of value) {
    const digit = /\d/.test(ch) ? Number(ch) : ch.charCodeAt(0) - 55;
    remainder = digit >= 10 ? (remainder * 100 + digit) % 97 : (remainder * 10 + digit) % 97;
  }
  return remainder;
}
