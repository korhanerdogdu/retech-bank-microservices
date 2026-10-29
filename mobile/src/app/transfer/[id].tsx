import { router, useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Alert, StyleSheet, Text, View } from 'react-native';
import { Button, Card, ErrorText, Field, Screen, TextLink } from '@/components/ui';
import type { Transaction } from '@/lib/api';
import { useSession } from '@/lib/auth';
import { customerReason, maskEmail, money, recipientLabel } from '@/lib/format';
import { formatIban } from '@/lib/iban';
import { colors, space, type } from '@/theme';

const IN_FLIGHT = ['PENDING', 'PROCESSING', 'COMPENSATING'];

export default function TransferStatus() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { api, guard } = useSession();
  const [tx, setTxState] = useState<Transaction | null>(null);
  // When the latest server copy arrived — anchors the OTP countdown
  const [receivedAt, setReceivedAt] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const setTx = useCallback((t: Transaction) => {
    setTxState(t);
    setReceivedAt(Date.now());
  }, []);

  const load = useCallback(() => {
    guard(api.transaction(id)).then(
      (t) => {
        setTx(t);
        setError(null);
      },
      (e: Error) => setError(e.message),
    );
  }, [api, guard, id, setTx]);

  useEffect(load, [load]);

  // Poll quickly while the bank is working, slowly while waiting for the customer
  useEffect(() => {
    if (!tx) return;
    const busy = IN_FLIGHT.includes(tx.status);
    if (!busy && tx.status !== 'PENDING_VERIFICATION') return;
    const timer = setTimeout(load, busy ? 800 : 5000);
    return () => clearTimeout(timer);
  }, [tx, load]);

  if (!tx) {
    return (
      <Screen>
        {error ? <ErrorText>{error}</ErrorText> : <Text style={type.small}>Loading the transfer…</Text>}
      </Screen>
    );
  }

  const finished = !IN_FLIGHT.includes(tx.status) && tx.status !== 'PENDING_VERIFICATION';

  return (
    <Screen>
      <View style={{ gap: space.xs }}>
        <Text style={type.small}>To {recipientLabel(tx)}</Text>
        <Text style={type.display} accessibilityRole="header">
          {money(tx.amount)}
        </Text>
      </View>

      <Progress tx={tx} />

      <Card>
        <Outcome tx={tx} receivedAt={receivedAt} onUpdate={setTx} />
      </Card>

      {finished && <Button title="Back to home" tone="secondary" onPress={() => router.dismissTo('/')} />}
    </Screen>
  );
}

function Progress({ tx }: { tx: Transaction }) {
  const needsCode =
    tx.status === 'PENDING_VERIFICATION' ||
    !!tx.failureReason?.match(/OTP|code|customer/i);
  const steps = ['Sent', 'Security check', ...(needsCode ? ['Your code'] : []), 'Done'];
  const failed = ['FAILED', 'FLAGGED', 'COMPENSATING'].includes(tx.status);
  const current =
    tx.status === 'PENDING' ? 0 : tx.status === 'PROCESSING' ? 1 : tx.status === 'PENDING_VERIFICATION' ? 2 : steps.length - 1;

  return (
    <View style={styles.progress} accessibilityLabel={`Step ${current + 1} of ${steps.length}: ${steps[current]}`}>
      {steps.map((step, i) => {
        const last = i === steps.length - 1;
        const color =
          i < current || tx.status === 'COMPLETED'
            ? colors.ok
            : i === current
              ? failed
                ? colors.bad
                : colors.wait
              : colors.rule;
        return (
          <View key={step} style={styles.step}>
            <View style={[styles.stepBar, { backgroundColor: color }]} />
            <Text style={[styles.stepText, i === current && { color: colors.ink, fontWeight: '700' }]}>
              {last && failed ? 'Cancelled' : step}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

interface OutcomeProps {
  tx: Transaction;
  receivedAt: number;
  onUpdate: (t: Transaction) => void;
}

function Outcome({ tx, receivedAt, onUpdate }: OutcomeProps) {
  switch (tx.status) {
    case 'PENDING':
    case 'PROCESSING':
      return (
        <>
          <Text style={type.heading}>Checking your transfer</Text>
          <Text style={type.small}>This usually takes a second.</Text>
        </>
      );
    case 'PENDING_VERIFICATION':
      return <VerifyCode tx={tx} receivedAt={receivedAt} onUpdate={onUpdate} />;
    case 'COMPLETED':
      return (
        <>
          <Text style={[type.heading, { color: colors.ok }]}>Money sent</Text>
          <Text style={type.body}>
            {money(tx.amount)} is on its way to{' '}
            {tx.receiverIban ? formatIban(tx.receiverIban) : `account ${tx.receiverAccountNumber}`}.
          </Text>
        </>
      );
    case 'COMPENSATING':
      return (
        <>
          <Text style={type.heading}>Cancelling and refunding</Text>
          <Text style={type.body}>{customerReason(tx.failureReason)}</Text>
          <Text style={type.small}>The money is being returned to your account.</Text>
        </>
      );
    case 'FLAGGED':
      return (
        <>
          <Text style={[type.heading, { color: colors.bad }]}>Transfer cancelled</Text>
          <Text style={type.body}>{customerReason(tx.failureReason)}.</Text>
          <Text style={type.body}>{money(tx.amount)} has been returned to your account.</Text>
          {tx.failureReason?.includes('blocked') ? (
            <Text style={type.small}>For your security the account is blocked. Contact Retech Bank to unblock it.</Text>
          ) : null}
        </>
      );
    case 'FAILED':
      return (
        <>
          <Text style={[type.heading, { color: colors.bad }]}>Transfer not sent</Text>
          <Text style={type.body}>{customerReason(tx.failureReason)}</Text>
          <Text style={type.small}>No money left your account.</Text>
        </>
      );
  }
}

function VerifyCode({ tx, receivedAt, onUpdate }: OutcomeProps) {
  const { user, api, guard } = useSession();
  const [code, setCode] = useState('');
  const [now, setNow] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The server says how many seconds were left when the data arrived; count down from there
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const remainingAtReceipt = tx.verificationSecondsRemaining ?? 300;
  const secondsLeft = Math.max(0, remainingAtReceipt - Math.floor((now - receivedAt) / 1000));

  const submit = async () => {
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from the email.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onUpdate(await guard(api.verify(tx.id, code)));
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  const report = () =>
    Alert.alert(
      "Didn't make this transfer?",
      `We'll cancel it, return ${money(tx.amount)} to your account and block the account until you contact us.`,
      [
        { text: 'Keep the transfer', style: 'cancel' },
        {
          text: 'Cancel and block',
          style: 'destructive',
          onPress: async () => {
            setBusy(true);
            try {
              onUpdate(await guard(api.reportFraud(tx.id)));
            } catch (e) {
              setError((e as Error).message);
              setBusy(false);
            }
          },
        },
      ],
    );

  if (secondsLeft === 0) {
    return (
      <>
        <Text style={type.heading}>The code has expired</Text>
        <Text style={type.body}>The transfer will be cancelled and the money returned to your account in a moment.</Text>
      </>
    );
  }

  const minutes = Math.floor(secondsLeft / 60);
  const seconds = String(secondsLeft % 60).padStart(2, '0');

  return (
    <>
      <Text style={type.heading}>Confirm it’s you</Text>
      <Text style={type.body}>
        This transfer looks unusual, so we paused it. We sent a 6-digit code to {maskEmail(user.email)}.
      </Text>
      <Field
        label="Verification code"
        value={code}
        onChangeText={(v) => setCode(v.replace(/\D/g, '').slice(0, 6))}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="one-time-code"
        maxLength={6}
        autoFocus
        returnKeyType="done"
        onSubmitEditing={submit}
        style={styles.codeInput}
        hint={`Expires in ${minutes}:${seconds}. A wrong code cancels the transfer and blocks the account.`}
      />
      <ErrorText>{error}</ErrorText>
      <Button title="Confirm transfer" onPress={submit} busy={busy} />
      {__DEV__ ? (
        <Text style={styles.devHint}>
          Development: codes arrive in the Mailpit inbox on your computer at http://localhost:8025.
        </Text>
      ) : null}
      <View style={styles.reportRow}>
        <TextLink title="I didn't make this transfer" tone="bad" onPress={report} />
      </View>
    </>
  );
}

const styles = StyleSheet.create({
  progress: { flexDirection: 'row', gap: space.xs },
  step: { flex: 1, gap: space.xs },
  stepBar: { height: 4, borderRadius: 2 },
  stepText: { fontSize: 13, color: colors.inkSoft },
  codeInput: { fontSize: 30, fontWeight: '800', letterSpacing: 10, textAlign: 'center', minHeight: 64 },
  devHint: { ...type.small, backgroundColor: '#FBF3E4', borderRadius: 8, padding: space.sm },
  reportRow: { borderTopWidth: 1, borderTopColor: colors.rule, paddingTop: space.md },
});
