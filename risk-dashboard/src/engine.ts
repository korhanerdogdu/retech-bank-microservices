// Thin typed wrapper around the risk-core WebAssembly build.
// Simulation only: nothing computed here is sent to, or trusted by, the bank.
import init, { WasmRiskEngine, defaultConfig } from './wasm/pkg/risk_wasm';

export type Decision = 'ALLOW' | 'REVIEW' | 'BLOCK';

export interface RiskConfig {
  maxTransactionsPerMinute: number;
  suspiciousAmountMultiplier: string;
  maxBalancePercentage: string;
  blockVelocityMultiplier: number;
  blockMinTriggeredRules: number;
}

export interface SimulatedTransaction {
  accountNumber: string;
  amount: string;
  balance: string;
  velocityCount: number;
  averageAmount: string | null;
}

export interface TriggeredRule {
  code: string;
  title: string;
  message: string;
}

export interface Assessment {
  transactionId: string | null;
  decision: Decision;
  reason: string | null;
  triggeredRules: TriggeredRule[];
  updatedAverageAmount: string | null;
}

export interface Simulator {
  evaluate(tx: SimulatedTransaction): Assessment;
  benchmark(tx: SimulatedTransaction, iterations: number): number;
  free(): void;
}

let ready: Promise<unknown> | null = null;

export async function loadWasm(): Promise<RiskConfig> {
  ready ??= init();
  await ready;
  return defaultConfig() as RiskConfig;
}

/** Builds an engine for a config. Throws with the engine's message if the config is invalid. */
export function createSimulator(config: RiskConfig): Simulator {
  const engine = new WasmRiskEngine(config);
  return {
    evaluate: (tx) => engine.evaluate(tx) as Assessment,
    benchmark: (tx, iterations) => engine.benchmark(tx, iterations),
    free: () => engine.free(),
  };
}
