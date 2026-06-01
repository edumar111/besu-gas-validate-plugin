package com.lacnet.besu.gas.usage;

/**
 * Señal de que toca commitear el uso acumulado on-chain (Fase 2). La dispara el
 * {@code UsageBlockListener} cada {@code commitEveryBlocks} bloques. En el nodo recorder la
 * implementa el {@code UsageCommitter} (arma, firma y envía la TX {@code recordUsageBatch}); en los
 * demás nodos es un no-op (no hay committer).
 */
@FunctionalInterface
public interface CommitTrigger {
    void onCommitDue(long blockNumber);
}
