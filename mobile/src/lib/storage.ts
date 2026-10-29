import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

/**
 * Session storage. On phones the token lives in the device keychain / keystore
 * (expo-secure-store). SecureStore has no web implementation, so the browser
 * build — meant for quick checks only — falls back to localStorage.
 */
const web = Platform.OS === 'web';

export async function getItem(key: string): Promise<string | null> {
  if (web) {
    try {
      return globalThis.localStorage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  }
  return SecureStore.getItemAsync(key);
}

export async function setItem(key: string, value: string): Promise<void> {
  if (web) {
    try {
      globalThis.localStorage?.setItem(key, value);
    } catch {
      // storage blocked (private mode) — the session lasts until the tab closes
    }
    return;
  }
  await SecureStore.setItemAsync(key, value);
}

export async function deleteItem(key: string): Promise<void> {
  if (web) {
    try {
      globalThis.localStorage?.removeItem(key);
    } catch {
      // nothing stored
    }
    return;
  }
  await SecureStore.deleteItemAsync(key);
}
