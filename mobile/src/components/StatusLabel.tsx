import { Text } from 'react-native';
import type { TransactionStatus } from '@/lib/api';
import { colors } from '@/theme';

const LABELS: Record<TransactionStatus, { text: string; color: string }> = {
  PENDING: { text: 'Starting', color: colors.wait },
  PROCESSING: { text: 'Security check', color: colors.wait },
  PENDING_VERIFICATION: { text: 'Waiting for your code', color: colors.wait },
  COMPLETED: { text: 'Completed', color: colors.ok },
  FAILED: { text: 'Not sent', color: colors.bad },
  COMPENSATING: { text: 'Refunding', color: colors.wait },
  FLAGGED: { text: 'Cancelled and refunded', color: colors.bad },
};

export function StatusLabel({ status }: { status: TransactionStatus }) {
  const label = LABELS[status];
  return <Text style={{ color: label.color, fontWeight: '600', fontSize: 14 }}>{label.text}</Text>;
}
