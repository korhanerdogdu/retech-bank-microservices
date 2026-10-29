import { forwardRef, type ComponentProps, type ComponentRef, type ReactNode } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type TextInputProps,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { colors, space, type } from '@/theme';

/** Scrollable screen that keeps inputs above the keyboard. */
export function Screen({
  children,
  refreshControl,
}: {
  children: ReactNode;
  refreshControl?: ComponentProps<typeof ScrollView>['refreshControl'];
}) {
  return (
    <SafeAreaView style={styles.safe} edges={['bottom', 'left', 'right']}>
      {/* Android is edge-to-edge, so the window no longer resizes for the keyboard on its own */}
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
        <ScrollView
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          refreshControl={refreshControl}
        >
          {children}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

// inverse / outlineInverse are for dark (ink) backgrounds
type ButtonTone = 'primary' | 'secondary' | 'danger' | 'inverse' | 'outlineInverse';

export function Button(props: {
  title: string;
  onPress: () => void;
  tone?: ButtonTone;
  busy?: boolean;
  disabled?: boolean;
}) {
  const tone = props.tone ?? 'primary';
  const disabled = props.disabled || props.busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!disabled, busy: !!props.busy }}
      onPress={props.onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.button,
        tone === 'primary' && styles.buttonPrimary,
        tone === 'secondary' && styles.buttonSecondary,
        tone === 'danger' && styles.buttonDanger,
        tone === 'inverse' && styles.buttonInverse,
        tone === 'outlineInverse' && styles.buttonOutlineInverse,
        (pressed || disabled) && styles.buttonDimmed,
      ]}
    >
      {props.busy ? (
        <ActivityIndicator color={tone === 'secondary' || tone === 'inverse' ? colors.ink : colors.onInk} />
      ) : (
        <Text style={[styles.buttonText, (tone === 'secondary' || tone === 'inverse') && styles.buttonTextSecondary]}>
          {props.title}
        </Text>
      )}
    </Pressable>
  );
}

export function TextLink({ title, onPress, tone }: { title: string; onPress: () => void; tone?: 'bad' }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} hitSlop={12}>
      <Text style={[styles.link, tone === 'bad' && { color: colors.bad }]}>{title}</Text>
    </Pressable>
  );
}

export type TextInputRef = ComponentRef<typeof TextInput>;

export const Field = forwardRef<TextInputRef, TextInputProps & { label: string; hint?: string }>(function Field(
  { label, hint, style, ...input },
  ref,
) {
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        ref={ref}
        placeholderTextColor={colors.inkSoft}
        style={[styles.input, style]}
        accessibilityLabel={label}
        accessibilityHint={hint}
        {...input}
      />
      {hint ? <Text style={styles.hint}>{hint}</Text> : null}
    </View>
  );
});

export function ErrorText({ children }: { children: ReactNode }) {
  if (!children) return null;
  return (
    <Text style={styles.error} accessibilityRole="alert">
      {children}
    </Text>
  );
}

export function Card({ children }: { children: ReactNode }) {
  return <View style={styles.card}>{children}</View>;
}

export const ui = StyleSheet.create({
  stack: { gap: space.md },
  center: { alignItems: 'center', justifyContent: 'center' },
});

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.paper },
  flex: { flex: 1 },
  content: { padding: space.lg, paddingBottom: space.xl * 2, gap: space.lg },
  button: {
    minHeight: 52,
    borderRadius: 12,
    paddingHorizontal: space.lg,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonPrimary: { backgroundColor: colors.ink },
  buttonSecondary: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.rule },
  buttonDanger: { backgroundColor: colors.bad },
  buttonInverse: { backgroundColor: colors.onInk },
  buttonOutlineInverse: { backgroundColor: 'transparent', borderWidth: 1, borderColor: 'rgba(245,245,250,0.4)' },
  buttonDimmed: { opacity: 0.6 },
  buttonText: { color: colors.onInk, fontSize: 16, fontWeight: '700' },
  buttonTextSecondary: { color: colors.ink },
  link: { color: colors.action, fontSize: 16, fontWeight: '600' },
  field: { gap: space.xs },
  fieldLabel: { ...type.small, color: colors.inkSoft },
  input: {
    minHeight: 50,
    borderWidth: 1,
    borderColor: colors.rule,
    borderRadius: 10,
    paddingHorizontal: space.md,
    fontSize: 17,
    color: colors.ink,
    backgroundColor: colors.surface,
  },
  hint: { ...type.small, fontSize: 13 },
  error: { color: colors.bad, fontSize: 15, lineHeight: 21 },
  card: {
    backgroundColor: colors.surface,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: colors.rule,
    padding: space.lg,
    gap: space.md,
  },
});
