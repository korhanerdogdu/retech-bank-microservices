// Retech Bank: deep plum ink, a violet action colour, quiet cool paper.
// Status colours are reserved for transfer outcomes.
export const colors = {
  paper: '#F5F5FA',
  surface: '#FFFFFF',
  ink: '#1B1840',
  inkSoft: '#5B5F78',
  rule: '#DCDCE8',
  action: '#4B3FD1',
  onInk: '#F5F5FA',
  ok: '#1F7A52',
  wait: '#A86A12',
  bad: '#B3261E',
};

export const space = { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 };

export const type = {
  display: { fontSize: 40, fontWeight: '800' as const, letterSpacing: -0.5, color: colors.ink },
  title: { fontSize: 26, fontWeight: '700' as const, color: colors.ink },
  heading: { fontSize: 18, fontWeight: '700' as const, color: colors.ink },
  body: { fontSize: 16, lineHeight: 23, color: colors.ink },
  small: { fontSize: 14, lineHeight: 20, color: colors.inkSoft },
};
