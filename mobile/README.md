# Retech Bank — mobile app

Expo (SDK 58) + React Native + TypeScript + Expo Router. Sign up, log in, send money, and confirm
unusual transfers with the code Retech Bank emails you.

```
src/app/          screens (Expo Router)
  _layout.tsx     auth-guarded stack (Stack.Protected)
  welcome.tsx  sign-in.tsx  sign-up.tsx        signed out
  index.tsx  send.tsx  transfer/[id].tsx       signed in
src/lib/          API client, session (expo-secure-store), API URL, formatting
src/components/   shared UI
```

## Run it

1. Start the backend from the repository root: `docker compose up -d --build`.
2. Here: `npm install`, then **`npm start`** (the dev server runs on port 8088 — the default 8081
   belongs to account-service, so plain `npx expo start` would clash).
3. Open the app:
   - **Phone (Expo Go):** phone and PC on the same Wi-Fi, scan the QR code.
   - **Android emulator:** press `a` in the Expo terminal (or `npm run android`).
   - **Browser:** press `w` (or `npm run web`) for quick checks. On the web the login is kept in
     `localStorage` instead of the device keychain.

**"Project is incompatible with this version of Expo Go"** means Expo Go is older than SDK 58.
Update it from the Play Store on a phone; on an emulator, uninstall it
(`adb uninstall host.exp.exponent`) and press `a` — the Expo CLI installs the matching version.

The app finds the API Gateway on the PC that serves the bundle (port 8080). If that doesn't
work, copy `.env.example` to `.env` and set `EXPO_PUBLIC_API_URL` to `http://<your PC's IP>:8080`.

**Phone can't connect?** Windows Firewall may be blocking port 8080 on your Wi-Fi network. In an
administrator PowerShell:

```powershell
New-NetFirewallRule -DisplayName "Retech Bank gateway (dev)" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
```

## Try it

1. Open an account (any email works — Mailpit catches everything at http://localhost:8025 on the PC).
2. Open a second account from another device, or log out and sign up again, and note the IBAN on its home screen.
3. Send money. Over 90% of your balance, or 3× your usual amount, pauses the transfer.
4. Read the 6-digit code in Mailpit and type it in the app — or tap **I didn't make this transfer**.

## Checks

```powershell
npx tsc --noEmit
npx expo lint
npx expo-doctor
```
