import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { ActivityIndicator, View } from 'react-native';
import { AuthProvider, useAuth } from '@/lib/auth';
import { colors } from '@/theme';

export default function RootLayout() {
  return (
    <AuthProvider>
      <StatusBar style="dark" />
      <RootStack />
    </AuthProvider>
  );
}

function RootStack() {
  const { status } = useAuth();

  if (status === 'loading') {
    return (
      <View style={{ flex: 1, backgroundColor: colors.paper, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator color={colors.ink} />
      </View>
    );
  }

  const signedIn = status === 'signedIn';

  return (
    <Stack
      screenOptions={{
        headerStyle: { backgroundColor: colors.paper },
        headerTintColor: colors.ink,
        headerTitleStyle: { fontWeight: '700' },
        headerShadowVisible: false,
        contentStyle: { backgroundColor: colors.paper },
      }}
    >
      {/* Signed out: when the guard flips, the router moves to the first available screen */}
      <Stack.Protected guard={!signedIn}>
        <Stack.Screen name="welcome" options={{ headerShown: false }} />
        <Stack.Screen name="sign-in" options={{ title: 'Log in' }} />
        <Stack.Screen name="sign-up" options={{ title: 'Open an account' }} />
      </Stack.Protected>

      {/* Signed out users who hit a banking screen land on Welcome, not the first auth screen */}
      <Stack.Protected guard={signedIn} redirectTo="/welcome">
        <Stack.Screen name="index" options={{ title: 'Retech Bank' }} />
        <Stack.Screen name="send" options={{ title: 'Send money' }} />
        <Stack.Screen name="transfer/[id]" options={{ title: 'Transfer' }} />
      </Stack.Protected>
    </Stack>
  );
}
