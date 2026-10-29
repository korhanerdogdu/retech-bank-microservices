import { router } from 'expo-router';
import { useState } from 'react';
import { Text, View } from 'react-native';
import { Button, ErrorText, Field, Screen, TextLink, ui } from '@/components/ui';
import { useAuth } from '@/lib/auth';
import { parseAmount } from '@/lib/format';
import { type } from '@/theme';

export default function SignUp() {
  const { signUp } = useAuth();
  const [form, setForm] = useState({
    fullName: '',
    email: '',
    phone: '',
    password: '',
    initialDeposit: '10000',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));

  const submit = async () => {
    const deposit = parseAmount(form.initialDeposit);
    if (!form.fullName.trim() || !form.email.trim() || !form.phone.trim()) {
      setError('Fill in your name, email and phone.');
      return;
    }
    if (form.password.length < 8) {
      setError('Choose a password with at least 8 characters.');
      return;
    }
    if (!Number.isFinite(deposit) || deposit <= 0) {
      setError('Enter an opening deposit greater than zero.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await signUp({ ...form, initialDeposit: deposit });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Screen>
      <Text style={type.body}>
        Your account opens instantly. Verification codes for unusual transfers go to the email and phone you enter here.
      </Text>
      <View style={ui.stack}>
        <Field
          label="Full name"
          value={form.fullName}
          onChangeText={(v) => set({ fullName: v })}
          autoComplete="name"
          textContentType="name"
        />
        <Field
          label="Email"
          value={form.email}
          onChangeText={(v) => set({ email: v })}
          autoCapitalize="none"
          autoComplete="email"
          keyboardType="email-address"
          textContentType="emailAddress"
        />
        <Field
          label="Phone"
          hint="Include the country code, e.g. +905551234567, to also get codes by SMS."
          value={form.phone}
          onChangeText={(v) => set({ phone: v })}
          keyboardType="phone-pad"
          autoComplete="tel"
          textContentType="telephoneNumber"
        />
        <Field
          label="Password"
          hint="At least 8 characters."
          value={form.password}
          onChangeText={(v) => set({ password: v })}
          secureTextEntry
          autoComplete="new-password"
          textContentType="newPassword"
        />
        <Field
          label="Opening deposit (₺)"
          hint="Demo money to start with."
          value={form.initialDeposit}
          onChangeText={(v) => set({ initialDeposit: v })}
          keyboardType="decimal-pad"
        />
      </View>
      <ErrorText>{error}</ErrorText>
      <Button title="Open my account" onPress={submit} busy={busy} />
      <TextLink title="Already have an account? Log in" onPress={() => router.replace('/sign-in')} />
    </Screen>
  );
}
