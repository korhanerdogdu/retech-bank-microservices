import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  createSimulator,
  loadWasm,
  type Assessment,
  type Decision,
  type RiskConfig,
  type SimulatedTransaction,
  type Simulator,
} from './engine';

// ---------------------------------------------------------------------------
// Data

interface Facts {
  amount: number;
  balance: number;
  velocity: number;
  average: number;
  hasHistory: boolean;
}

interface Scenario {
  name: string;
  facts: Facts;
}

const SCENARIOS: Scenario[] = [
  { name: 'Everyday transfer', facts: { amount: 120, balance: 5000, velocity: 1, average: 100, hasHistory: true } },
  { name: 'First transfer ever', facts: { amount: 2500, balance: 5000, velocity: 1, average: 0, hasHistory: false } },
  { name: 'Unusually large', facts: { amount: 1500, balance: 20000, velocity: 1, average: 200, hasHistory: true } },
  { name: 'Emptying the account', facts: { amount: 4700, balance: 5000, velocity: 1, average: 4000, hasHistory: true } },
  { name: 'Rapid fire', facts: { amount: 50, balance: 5000, velocity: 7, average: 50, hasHistory: true } },
  { name: 'Bot burst', facts: { amount: 50, balance: 5000, velocity: 12, average: 50, hasHistory: true } },
  { name: 'Everything at once', facts: { amount: 4900, balance: 5000, velocity: 6, average: 300, hasHistory: true } },
];

const NEXT_STEP: Record<Decision, string> = {
  ALLOW: 'The transfer completes straight away.',
  REVIEW:
    'The money is held and the customer gets a 6-digit code by email. A correct code completes the transfer; a wrong code, no answer within 5 minutes or “I didn’t make this transfer” refunds it.',
  BLOCK: 'The transfer is cancelled, the money is refunded and the account is blocked until the customer contacts the bank.',
};

const lira = new Intl.NumberFormat('tr-TR', { style: 'currency', currency: 'TRY' });
const plain = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 2 });

function toTransaction(f: Facts): SimulatedTransaction {
  return {
    accountNumber: 'SIMULATION',
    amount: f.amount.toFixed(2),
    balance: f.balance.toFixed(2),
    velocityCount: Math.max(0, Math.round(f.velocity)),
    averageAmount: f.hasHistory ? f.average.toFixed(2) : null,
  };
}

/** Accepts "1.234,56", "10,5" or "1234.56", like the Retech Bank app. */
function parseAmount(text: string): number {
  const cleaned = text.replace(/[\s₺]/g, '');
  if (!cleaned) return NaN;
  if (cleaned.includes(',')) return Number(cleaned.replace(/\./g, '').replace(',', '.'));
  if (/^\d{1,3}(\.\d{3})+$/.test(cleaned)) return Number(cleaned.replace(/\./g, ''));
  return Number(cleaned);
}

/** "Bot burst" → "bot-burst", so each example has a shareable link such as /#bot-burst. */
const slug = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');

function scenarioFromHash(): Facts {
  const wanted = window.location.hash.slice(1);
  return (SCENARIOS.find((s) => slug(s.name) === wanted) ?? SCENARIOS[0]).facts;
}

function sameFacts(a: Facts, b: Facts) {
  return (
    a.amount === b.amount &&
    a.balance === b.balance &&
    a.velocity === b.velocity &&
    a.hasHistory === b.hasHistory &&
    (!a.hasHistory || a.average === b.average)
  );
}

// ---------------------------------------------------------------------------
// App

export default function App() {
  const [config, setConfig] = useState<RiskConfig | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [facts, setFacts] = useState<Facts>(scenarioFromHash);

  // Keep the address bar pointing at the example on screen (cleared once the facts are edited)
  useEffect(() => {
    const match = SCENARIOS.find((s) => sameFacts(s.facts, facts));
    const hash = match ? `#${slug(match.name)}` : '';
    if (window.location.hash !== hash) {
      window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}${hash}`);
    }
  }, [facts]);

  useEffect(() => {
    loadWasm().then(setConfig, (e: unknown) => setLoadError(String(e)));
  }, []);

  const engine = useMemo<{ simulator: Simulator | null; error: string | null }>(() => {
    if (!config) return { simulator: null, error: null };
    try {
      return { simulator: createSimulator(config), error: null };
    } catch (e) {
      return { simulator: null, error: e instanceof Error ? e.message : String(e) };
    }
  }, [config]);

  useEffect(() => () => engine.simulator?.free(), [engine]);

  const tx = toTransaction(facts);
  let assessment: Assessment | null = null;
  let evalError: string | null = engine.error;
  if (engine.simulator) {
    try {
      assessment = engine.simulator.evaluate(tx);
    } catch (e) {
      evalError = e instanceof Error ? e.message : String(e);
    }
  }

  const update = (patch: Partial<Facts>) => setFacts((f) => ({ ...f, ...patch }));

  return (
    <div className="page">
      <header className="masthead">
        <div className="brand-row">
          <p className="brand">Retech Bank</p>
          <p className="sim-badge">Simulation only. No real money moves.</p>
        </div>
        <h1>Risk Lab</h1>
        <p className="lede">
          Describe a transfer and see what the bank’s fraud rules decide, and why. The rules run in your browser from
          the same Rust code as the live risk engine, compiled to WebAssembly, so the answer here is the answer the bank
          would give.
        </p>
      </header>

      {loadError && (
        <p className="alert" role="alert">
          The rule engine could not load: {loadError}. Rebuild it with <code>npm run build:wasm</code>.
        </p>
      )}

      <nav className="scenarios" aria-label="Example transfers">
        {SCENARIOS.map((s) => (
          <button
            key={s.name}
            type="button"
            className="chip"
            aria-pressed={sameFacts(s.facts, facts)}
            onClick={() => setFacts(s.facts)}
          >
            {s.name}
          </button>
        ))}
      </nav>

      <main className="lab">
        <section className="facts" aria-labelledby="facts-title">
          <h2 id="facts-title" className="section-title">
            The transfer
          </h2>

          <NumberSlider
            label="Amount"
            value={facts.amount}
            min={0}
            max={Math.max(1000, Math.ceil((facts.balance * 1.2) / 100) * 100)}
            step={10}
            money
            onChange={(v) => update({ amount: v })}
          />
          <NumberSlider
            label="Balance before the transfer"
            value={facts.balance}
            min={0}
            max={100000}
            step={100}
            money
            onChange={(v) => update({ balance: v })}
          />
          <NumberSlider
            label="Transfers in the last 60 seconds, including this one"
            value={facts.velocity}
            min={1}
            max={20}
            step={1}
            onChange={(v) => update({ velocity: Math.round(v) })}
          />
          <NumberSlider
            label="Usual amount (running average)"
            value={facts.average}
            min={0}
            max={Math.max(10000, Math.ceil(facts.average / 1000) * 1000)}
            step={10}
            money
            disabled={!facts.hasHistory}
            onChange={(v) => update({ average: v })}
          />
          <label className="check">
            <input
              type="checkbox"
              checked={!facts.hasHistory}
              onChange={(e) => update({ hasHistory: !e.target.checked })}
            />
            <span>This account has never sent money before</span>
          </label>

          {config && <RuleSettings config={config} onChange={setConfig} />}
        </section>

        <section className="result" aria-live="polite" aria-labelledby="verdict-title">
          <h2 id="verdict-title" className="visually-hidden">
            Decision
          </h2>
          {evalError && (
            <p className="alert" role="alert">
              {evalError}
            </p>
          )}
          {!assessment && !evalError && <p className="muted">Loading the rule engine…</p>}
          {assessment && config && (
            <>
              <Verdict assessment={assessment} />
              <Rules facts={facts} config={config} assessment={assessment} />
            </>
          )}
        </section>
      </main>

      {config && engine.simulator && (
        <section className="lower">
          <DecisionGuide config={config} />
          <ScenarioTable simulator={engine.simulator} current={facts} onPick={setFacts} />
        </section>
      )}

      {engine.simulator && !evalError && <SpeedTest simulator={engine.simulator} tx={tx} />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Inputs

function NumberSlider(props: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  money?: boolean;
  disabled?: boolean;
  onChange: (v: number) => void;
}) {
  const [text, setText] = useState(plain.format(props.value));
  const [editing, setEditing] = useState(false);
  const shown = editing ? text : plain.format(props.value);
  const id = props.label.replace(/\W+/g, '-').toLowerCase();

  return (
    <div className={`field ${props.disabled ? 'field--disabled' : ''}`}>
      <label className="field-label" htmlFor={id}>
        {props.label}
      </label>
      <div className="field-row">
        <div className="field-control">
          {props.money && (
            <span className="field-unit" aria-hidden="true">
              ₺
            </span>
          )}
          <input
            id={id}
            inputMode="decimal"
            value={shown}
            disabled={props.disabled}
            onFocus={() => {
              setText(plain.format(props.value));
              setEditing(true);
            }}
            onBlur={() => setEditing(false)}
            onChange={(e) => {
              setText(e.target.value);
              const v = parseAmount(e.target.value);
              if (Number.isFinite(v) && v >= 0) props.onChange(v);
            }}
          />
        </div>
        <input
          type="range"
          className="slider"
          aria-label={`${props.label} slider`}
          min={props.min}
          max={props.max}
          step={props.step}
          value={Math.min(props.value, props.max)}
          disabled={props.disabled}
          onChange={(e) => props.onChange(Number(e.target.value))}
        />
      </div>
    </div>
  );
}

function RuleSettings({ config, onChange }: { config: RiskConfig; onChange: (c: RiskConfig) => void }) {
  const set = (patch: Partial<RiskConfig>) => onChange({ ...config, ...patch });
  const settings: { label: string; value: string; apply: (v: string) => void }[] = [
    {
      label: 'Transfers allowed per minute',
      value: String(config.maxTransactionsPerMinute),
      apply: (v) => set({ maxTransactionsPerMinute: Number(v) || 0 }),
    },
    {
      label: 'Unusual when above this multiple of the usual amount',
      value: config.suspiciousAmountMultiplier,
      apply: (v) => set({ suspiciousAmountMultiplier: v.replace(',', '.') }),
    },
    {
      label: 'Unusual when above this share of the balance (0–1)',
      value: config.maxBalancePercentage,
      apply: (v) => set({ maxBalancePercentage: v.replace(',', '.') }),
    },
    {
      label: 'Block when the per-minute limit is exceeded this many times over',
      value: String(config.blockVelocityMultiplier),
      apply: (v) => set({ blockVelocityMultiplier: Number(v) || 0 }),
    },
    {
      label: 'Block when at least this many rules fire',
      value: String(config.blockMinTriggeredRules),
      apply: (v) => set({ blockMinTriggeredRules: Number(v) || 0 }),
    },
  ];

  return (
    <details className="settings">
      <summary>Rule settings</summary>
      <p className="muted small">These start at the bank’s live values. Changes only affect this page.</p>
      {settings.map((s) => (
        <label key={s.label} className="setting">
          <span className="field-label">{s.label}</span>
          <input defaultValue={s.value} inputMode="decimal" onChange={(e) => s.apply(e.target.value)} />
        </label>
      ))}
    </details>
  );
}

// ---------------------------------------------------------------------------
// Result

function Verdict({ assessment }: { assessment: Assessment }) {
  const d = assessment.decision;
  const fired = assessment.triggeredRules.length;
  return (
    <div className={`verdict verdict--${d.toLowerCase()}`}>
      <div className="verdict-head">
        <DecisionIcon decision={d} />
        <p className="verdict-word">{d}</p>
      </div>
      <p className="verdict-next">{NEXT_STEP[d]}</p>
      <dl className="verdict-facts">
        <div>
          <dt>Rules fired</dt>
          <dd>{fired} of 3</dd>
        </div>
        <div>
          <dt>Customer is told</dt>
          <dd>{assessment.reason ? `“${assessment.reason}”` : 'Nothing. The transfer just goes through.'}</dd>
        </div>
        <div>
          <dt>Usual amount afterwards</dt>
          <dd>
            {assessment.updatedAverageAmount
              ? lira.format(Number(assessment.updatedAverageAmount))
              : 'Unchanged, because the speed limit was hit'}
          </dd>
        </div>
      </dl>
    </div>
  );
}

/** Check, exclamation or cross in a tinted disc — shape as well as colour tells the decisions apart. */
function DecisionIcon({ decision }: { decision: Decision }) {
  return (
    <span className="verdict-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" strokeWidth="3"
        strokeLinecap="round" strokeLinejoin="round">
        {decision === 'ALLOW' && <path d="M5 12.5l4.5 4.5L19 7.5" />}
        {decision === 'REVIEW' && (
          <>
            <path d="M12 6v8" />
            <path d="M12 18.5v.01" />
          </>
        )}
        {decision === 'BLOCK' && (
          <>
            <path d="M7 7l10 10" />
            <path d="M17 7L7 17" />
          </>
        )}
      </svg>
    </span>
  );
}

function Rules({ facts, config, assessment }: { facts: Facts; config: RiskConfig; assessment: Assessment }) {
  // Limits are recomputed for drawing only; whether a rule fired always comes from the engine.
  const fired = new Set(assessment.triggeredRules.map((r) => r.code));
  const multiplier = Number(config.suspiciousAmountMultiplier);
  const share = Number(config.maxBalancePercentage);
  const amountLimit = facts.hasHistory ? facts.average * multiplier : null;
  const balanceLimit = facts.balance > 0 ? facts.balance * share : null;

  return (
    <div className="rules">
      <h3 className="section-title">The three rules</h3>

      <RuleRow
        title="Speed"
        rule={`More than ${config.maxTransactionsPerMinute} transfers in 60 seconds`}
        fired={fired.has('VELOCITY')}
        applies
      >
        <VelocityDots
          count={Math.round(facts.velocity)}
          limit={config.maxTransactionsPerMinute}
          blockAt={config.maxTransactionsPerMinute * config.blockVelocityMultiplier}
        />
      </RuleRow>

      <RuleRow
        title="Unusual amount"
        rule={`More than ${plain.format(multiplier)}× the usual amount`}
        fired={fired.has('UNUSUAL_AMOUNT')}
        applies={amountLimit !== null}
        inactive="No earlier transfers, so this one only sets the usual amount."
      >
        {amountLimit !== null && <Meter value={facts.amount} limit={amountLimit} />}
      </RuleRow>

      <RuleRow
        title="Share of the balance"
        rule={`More than ${plain.format(share * 100)}% of the balance before the transfer`}
        fired={fired.has('BALANCE_PERCENTAGE')}
        applies={balanceLimit !== null}
        inactive="The balance is empty, so this rule doesn’t apply."
      >
        {balanceLimit !== null && <Meter value={facts.amount} limit={balanceLimit} />}
      </RuleRow>
    </div>
  );
}

function RuleRow(props: {
  title: string;
  rule: string;
  fired: boolean;
  applies: boolean;
  inactive?: string;
  children?: ReactNode;
}) {
  const state = props.fired ? 'fired' : props.applies ? 'ok' : 'off';
  const label = props.fired ? 'Fired' : props.applies ? 'Within limit' : 'Not applicable';
  return (
    <div className={`rule rule--${state}`}>
      <div className="rule-head">
        <div>
          <p className="rule-title">{props.title}</p>
          <p className="rule-def">{props.rule}</p>
        </div>
        <span className={`pill pill--${state}`}>{label}</span>
      </div>
      {props.applies ? props.children : <p className="rule-def">{props.inactive}</p>}
    </div>
  );
}

/** Amount against a limit. The limit sits two thirds along, so overshoot stays visible. */
function Meter({ value, limit }: { value: number; limit: number }) {
  const ratio = limit > 0 ? value / limit : 0;
  const fill = Math.min(ratio / 1.5, 1) * 100;
  return (
    <div className="meter">
      <div className="meter-track" aria-hidden="true">
        <div className={`meter-fill ${ratio > 1 ? 'meter-fill--over' : ''}`} style={{ width: `${fill}%` }} />
        <div className="meter-limit" />
      </div>
      <p className="meter-text">
        <strong>{lira.format(value)}</strong> against a limit of {lira.format(limit)}
        <span className="muted"> ({plain.format(Math.round(ratio * 100))}% of the limit)</span>
      </p>
    </div>
  );
}

/** One dot per transfer this minute, with the review and block limits marked. */
function VelocityDots({ count, limit, blockAt }: { count: number; limit: number; blockAt: number }) {
  const slots = Math.max(blockAt + 3, count, 12);
  return (
    <div className="dots-wrap">
      <ol className="dots" aria-label={`${count} transfers this minute; review above ${limit}, block above ${blockAt}`}>
        {Array.from({ length: slots }, (_, i) => {
          const n = i + 1;
          const tone = n > count ? 'empty' : n > blockAt ? 'block' : n > limit ? 'review' : 'ok';
          return (
            <li key={n} className={`dot dot--${tone} ${n === limit || n === blockAt ? 'dot--edge' : ''}`}>
              {n === limit && <span className="dot-mark">limit</span>}
              {n === blockAt && <span className="dot-mark">block</span>}
            </li>
          );
        })}
      </ol>
      <p className="meter-text">
        <strong>{count}</strong> {count === 1 ? 'transfer' : 'transfers'} this minute
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Explanations and comparison

function DecisionGuide({ config }: { config: RiskConfig }) {
  const blockSpeed = config.maxTransactionsPerMinute * config.blockVelocityMultiplier;
  return (
    <section className="panel" aria-labelledby="guide-title">
      <h2 id="guide-title" className="section-title">
        How decisions are made
      </h2>
      <ul className="guide">
        <li>
          <span className="pill pill--allow">ALLOW</span>
          <span>No rule fires.</span>
        </li>
        <li>
          <span className="pill pill--review">REVIEW</span>
          <span>
            {config.blockMinTriggeredRules === 3
              ? 'One or two rules fire.'
              : config.blockMinTriggeredRules > 3
                ? `Between one and ${config.blockMinTriggeredRules - 1} rules fire.`
                : 'One rule fires.'}{' '}
            The money is held until the customer confirms with an emailed code.
          </span>
        </li>
        <li>
          <span className="pill pill--block">BLOCK</span>
          <span>
            {config.blockMinTriggeredRules === 3
              ? 'All three rules fire'
              : `${config.blockMinTriggeredRules} or more rules fire`}
            , or more than {blockSpeed} transfers arrive in a minute (clear automated abuse).
          </span>
        </li>
      </ul>
      <p className="muted small">
        The usual amount starts as the first transfer and then becomes the average of itself and each new transfer. It
        isn’t updated when the speed rule fires.
      </p>
    </section>
  );
}

function ScenarioTable({
  simulator,
  current,
  onPick,
}: {
  simulator: Simulator;
  current: Facts;
  onPick: (f: Facts) => void;
}) {
  const rows = SCENARIOS.map((s) => {
    try {
      return { s, a: simulator.evaluate(toTransaction(s.facts)) };
    } catch {
      return { s, a: null };
    }
  });

  return (
    <section className="panel" aria-labelledby="table-title">
      <h2 id="table-title" className="section-title">
        Every example at a glance
      </h2>
      <div className="table-scroll">
        <table className="scenario-table">
          <thead>
            <tr>
              <th scope="col">Example</th>
              <th scope="col">Decision</th>
              <th scope="col" className="num">
                Amount
              </th>
              <th scope="col" className="num">
                Balance
              </th>
              <th scope="col" className="num">
                Per minute
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ s, a }) => (
              <tr key={s.name} className={sameFacts(s.facts, current) ? 'is-current' : ''}>
                <th scope="row">
                  <button type="button" className="link" onClick={() => onPick(s.facts)}>
                    {s.name}
                  </button>
                </th>
                <td>{a ? <span className={`pill pill--${a.decision.toLowerCase()}`}>{a.decision}</span> : '—'}</td>
                <td className="num">{lira.format(s.facts.amount)}</td>
                <td className="num">{lira.format(s.facts.balance)}</td>
                <td className="num">{s.facts.velocity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Speed test

interface BenchResult {
  label: string;
  nsPerOp: number;
  opsPerSec: number;
}

function SpeedTest({ simulator, tx }: { simulator: Simulator; tx: SimulatedTransaction }) {
  const [iterations, setIterations] = useState(100_000);
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState<BenchResult[]>([]);

  const run = () => {
    setRunning(true);
    // Let the button state paint before the CPU-bound loop starts
    setTimeout(() => {
      const measure = (label: string, n: number, body: () => void): BenchResult => {
        const start = performance.now();
        body();
        const ms = performance.now() - start;
        return { label, nsPerOp: (ms * 1e6) / n, opsPerSec: n / (ms / 1000) };
      };
      simulator.benchmark(tx, 5_000); // warm-up
      const inside = measure('Inside WebAssembly', iterations, () => {
        simulator.benchmark(tx, iterations);
      });
      const calls = Math.min(iterations, 20_000);
      const crossing = measure('One JavaScript call per transfer', calls, () => {
        for (let i = 0; i < calls; i++) simulator.evaluate(tx);
      });
      setResults([inside, crossing]);
      setRunning(false);
    }, 16);
  };

  return (
    <details className="panel speed" open>
      <summary>Speed test</summary>
      <div className="speed-body">
        <p className="muted small">
          Times the current transfer in this browser. For the native figure, run{' '}
          <code>cargo run --release -p risk-core --example bench</code> in <code>risk-engine</code>.
        </p>
        <div className="speed-controls">
          <label className="setting setting--inline">
            <span className="field-label">Evaluations</span>
            <select value={iterations} onChange={(e) => setIterations(Number(e.target.value))}>
              {[10_000, 100_000, 500_000, 1_000_000].map((n) => (
                <option key={n} value={n}>
                  {n.toLocaleString('tr-TR')}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="primary" onClick={run} disabled={running}>
            {running ? 'Running…' : 'Run speed test'}
          </button>
        </div>
        {results.length > 0 && (
          <table className="scenario-table">
            <thead>
              <tr>
                <th scope="col">Where it ran</th>
                <th scope="col" className="num">
                  Per evaluation
                </th>
                <th scope="col" className="num">
                  Per second
                </th>
              </tr>
            </thead>
            <tbody>
              {results.map((r) => (
                <tr key={r.label}>
                  <th scope="row">{r.label}</th>
                  <td className="num">{plain.format(Math.round(r.nsPerOp))} ns</td>
                  <td className="num">{plain.format(Math.round(r.opsPerSec))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </details>
  );
}
