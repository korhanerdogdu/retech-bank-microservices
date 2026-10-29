import Constants from 'expo-constants';
import { Platform } from 'react-native';

/**
 * Where the API Gateway is.
 *
 * 1. EXPO_PUBLIC_API_URL in mobile/.env wins (e.g. http://192.168.1.20:8080).
 * 2. In development the app is loaded from the Expo dev server on your PC, so the
 *    same host — your PC's LAN IP — also runs the gateway on port 8080.
 * 3. Fallbacks: the Android emulator reaches the PC at 10.0.2.2.
 */
function resolveApiUrl(): string {
  const fromEnv = process.env.EXPO_PUBLIC_API_URL;
  if (fromEnv) return fromEnv.replace(/\/+$/, '');

  const hostUri = Constants.expoConfig?.hostUri ?? Constants.platform?.hostUri;
  const host = hostUri?.split(':')[0];
  if (host) return `http://${host}:8080`;

  return Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080';
}

export const API_URL = resolveApiUrl();
