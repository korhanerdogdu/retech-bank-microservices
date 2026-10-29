import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Button, Card, ErrorText, Field, Screen, TextLink, ui } from '@/components/ui';
import type { Account, Recipient } from '@/lib/api';
import { useSession } from '@/lib/auth';
import { money, parseAmount } from '@/lib/format';
import { formatIban, isValidIban, normalizeIban } from '@/lib/iban';
import { colors, space, type } from '@/theme';

export default function SendMoney() {
  const { user, api, guard } = useSession();
  const [iban, setIban] = useState('');
  const [recipient, setRecipient] = useState<Recipient | null>(null);
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [account, setAccount] = useState<Account | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    guard(api.account(user.accountNumber)).then(setAccount, () => undefined);
  }, [api, guard, user.accountNumber]);

  const findRecipient = async () => {
    const normalized = normalizeIban(iban);
    setIban(formatIban(normalized));
    if (!normalized) {
      setError('Enter the IBAN of the account you want to send money to.');
      return;
    }
    if (!isValidIban(normalized)) {
      setError("That isn't a valid IBAN. Check it for typos — Turkish IBANs start with TR and have 26 characters.");
      return;
    }
    if (account && normalized === account.iban) {
      setError("That's your own IBAN. Enter the IBAN of the account you want to send money to.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // The bank checks the IBAN too, and whether it belongs to a Retech Bank account
      const found = await guard(api.lookupRecipient(normalized));
      if (!found.canReceive) {
        setError('That account is closed and cannot receive money.');
      } else {
        setRecipient(found);
      }
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  const send = async () => {
    const value = parseAmount(amount);
    if (!Number.isFinite(value) || value <= 0) {
      setError('Enter an amount greater than zero.');
      return;
    }
    if (account && value > account.balance) {
      setError(`You have ${money(account.balance)} available.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const tx = await guard(
        api.transfer({
          senderAccountNumber: user.accountNumber,
          receiverIban: recipient!.iban,
          amount: value,
          description: note.trim(),
        }),
      );
      router.replace({ pathname: '/transfer/[id]', params: { id: tx.id } });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  if (!recipient) {
    return (
      <Screen>
        <Text style={type.body}>Who are you sending money to?</Text>
        <Field
          label="Recipient's IBAN"
          hint="Ask them for their IBAN — it's shown on their Retech Bank home screen."
          value={iban}
          // Keep the text exactly as typed: rewriting a controlled input on every keystroke
          // races Android's native input and garbles fast typing. Group into fours on blur.
          onChangeText={setIban}
          onBlur={() => setIban((current) => formatIban(current))}
          autoCapitalize="characters"
          autoCorrect={false}
          autoComplete="off"
          maxLength={34 + 8}
          placeholder="TR00 0000 0000 0000 0000 0000 00"
          returnKeyType="next"
          onSubmitEditing={findRecipient}
          style={styles.ibanInput}
          autoFocus
        />
        <ErrorText>{error}</ErrorText>
        <Button title="Continue" onPress={findRecipient} busy={busy} />
      </Screen>
    );
  }

  const value = parseAmount(amount);
  return (
    <Screen>
      <Card>
        <Text style={type.small}>Sending to</Text>
        <Text style={type.title}>{recipient.displayName}</Text>
        <Text style={[type.small, styles.ibanText]} selectable>
          {formatIban(recipient.iban)}
        </Text>
        <TextLink
          title="Change recipient"
          onPress={() => {
            setRecipient(null);
            setError(null);
          }}
        />
      </Card>

      <View style={ui.stack}>
        <Field
          label="Amount (₺)"
          hint={account ? `${money(account.balance)} available` : undefined}
          value={amount}
          onChangeText={setAmount}
          keyboardType="decimal-pad"
          style={styles.amountInput}
          autoFocus
          returnKeyType="send"
          onSubmitEditing={send}
        />
        <Field label="Note (optional)" value={note} onChangeText={setNote} maxLength={140} placeholder="Rent, dinner…" />
      </View>

      <ErrorText>{error}</ErrorText>
      <Button
        title={value > 0 ? `Send ${money(value)} to ${recipient.displayName}` : 'Send money'}
        onPress={send}
        busy={busy}
      />
      <Text style={styles.footnote}>
        Large or unusual transfers are paused until you confirm them with a code we send to your email.
      </Text>
    </Screen>
  );
}

const styles = StyleSheet.create({
  ibanInput: { fontSize: 18, letterSpacing: 0.5, fontVariant: ['tabular-nums'] },
  ibanText: { letterSpacing: 0.5, fontVariant: ['tabular-nums'] },
  amountInput: { fontSize: 28, fontWeight: '700', minHeight: 60 },
  footnote: { ...type.small, color: colors.inkSoft, marginTop: -space.sm },
});
