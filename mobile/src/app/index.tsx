import { router, Stack, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { Button, ErrorText, Screen, TextLink } from '@/components/ui';
import { StatusLabel } from '@/components/StatusLabel';
import type { Account, Transaction } from '@/lib/api';
import { useSession } from '@/lib/auth';
import { firstName, money, recipientLabel } from '@/lib/format';
import { formatIban } from '@/lib/iban';
import { colors, space, type } from '@/theme';

export default function Home() {
  const { user, api, guard, signOut } = useSession();
  const [account, setAccount] = useState<Account | null>(null);
  const [history, setHistory] = useState<Transaction[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      const [acc, txs] = await guard(Promise.all([api.account(user.accountNumber), api.history(user.accountNumber)]));
      setAccount(acc);
      setHistory(txs);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [api, guard, user.accountNumber]);

  // Refresh whenever the screen comes back into view (e.g. after a transfer)
  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const refresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  return (
    <Screen refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={colors.ink} />}>
      <Stack.Screen options={{ headerRight: () => <TextLink title="Log out" onPress={signOut} /> }} />

      <Text style={type.title} accessibilityRole="header">
        Hi, {firstName(user.fullName)}
      </Text>

      <View style={styles.balanceCard} accessible accessibilityLabel={account ? `Balance ${money(account.balance)}` : 'Loading balance'}>
        <Text style={styles.balanceLabel}>Available balance</Text>
        <Text style={styles.balance} adjustsFontSizeToFit numberOfLines={1}>
          {account ? money(account.balance) : '—'}
        </Text>
        <View style={styles.accountRow}>
          <Text style={styles.accountMeta}>Savings account</Text>
        </View>
        {/* The IBAN is what other people need to send this account money */}
        <Text style={styles.ibanLabel}>IBAN</Text>
        <Text style={styles.iban} selectable accessibilityLabel={account ? `IBAN ${account.iban}` : undefined}>
          {account?.iban ? formatIban(account.iban) : '—'}
        </Text>
      </View>

      <ErrorText>{error}</ErrorText>

      {account?.status === 'BLOCKED' ? (
        <View style={styles.blocked} accessibilityRole="alert">
          <Text style={[type.heading, { color: colors.bad }]}>Your account is blocked</Text>
          <Text style={[type.body, { color: colors.bad }]}>
            Transfers are paused for your security. Contact Retech Bank to unblock it.
          </Text>
        </View>
      ) : (
        <Button title="Send money" onPress={() => router.push('/send')} disabled={!account} />
      )}

      <View style={styles.section}>
        <Text style={type.heading} accessibilityRole="header">
          Recent transfers
        </Text>
        {history.length === 0 ? (
          <Text style={type.small}>Money you send will show up here.</Text>
        ) : (
          history.slice(0, 15).map((tx) => (
            <Pressable
              key={tx.id}
              accessibilityRole="button"
              accessibilityLabel={`${money(tx.amount)} to ${recipientLabel(tx)}`}
              onPress={() => router.push({ pathname: '/transfer/[id]', params: { id: tx.id } })}
              style={({ pressed }) => [styles.row, pressed && { opacity: 0.6 }]}
            >
              <View style={styles.rowMain}>
                <Text style={type.body}>To {recipientLabel(tx)}</Text>
                <Text style={[type.body, styles.rowAmount]}>{money(tx.amount)}</Text>
              </View>
              <View style={styles.rowMain}>
                <StatusLabel status={tx.status} />
                {tx.description ? (
                  <Text style={type.small} numberOfLines={1}>
                    {tx.description}
                  </Text>
                ) : null}
              </View>
            </Pressable>
          ))
        )}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  balanceCard: { backgroundColor: colors.ink, borderRadius: 20, padding: space.lg, gap: space.xs },
  balanceLabel: { color: colors.onInk, opacity: 0.75, fontSize: 15 },
  balance: { color: colors.onInk, fontSize: 44, fontWeight: '800', letterSpacing: -1, fontVariant: ['tabular-nums'] },
  accountRow: { marginTop: space.md },
  accountMeta: { color: colors.onInk, fontSize: 15, fontWeight: '600' },
  ibanLabel: { color: colors.onInk, opacity: 0.7, fontSize: 13, marginTop: space.xs },
  iban: { color: colors.onInk, fontSize: 16, letterSpacing: 0.5, fontVariant: ['tabular-nums'] },
  blocked: {
    borderWidth: 1,
    borderColor: colors.bad,
    borderRadius: 14,
    padding: space.md,
    gap: space.xs,
    backgroundColor: '#FBEDEC',
  },
  section: { gap: space.sm },
  row: { paddingVertical: space.md, borderBottomWidth: 1, borderBottomColor: colors.rule, gap: space.xs },
  rowMain: { flexDirection: 'row', justifyContent: 'space-between', gap: space.md },
  rowAmount: { fontWeight: '700', fontVariant: ['tabular-nums'] },
});
