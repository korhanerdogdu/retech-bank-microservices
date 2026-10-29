import { router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Button } from '@/components/ui';
import { colors, space, type } from '@/theme';

export default function Welcome() {
  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar style="light" />
      <View style={styles.brand}>
        <Text style={styles.wordmark} accessibilityRole="header">
          Retech{'\n'}Bank
        </Text>
        <Text style={styles.tagline}>
          Send money in seconds. If a transfer ever looks unusual, we pause it and ask you to confirm with a code.
        </Text>
      </View>

      <View style={styles.actions}>
        <Button title="Open an account" tone="inverse" onPress={() => router.push('/sign-up')} />
        <Button title="Log in" tone="outlineInverse" onPress={() => router.push('/sign-in')} />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.ink, padding: space.lg, justifyContent: 'space-between' },
  brand: { flex: 1, justifyContent: 'center', gap: space.lg },
  wordmark: { fontSize: 64, lineHeight: 64, fontWeight: '900', letterSpacing: -2, color: colors.onInk },
  tagline: { ...type.body, color: colors.onInk, opacity: 0.85, maxWidth: 340 },
  actions: { gap: space.md, paddingBottom: space.md },
});
