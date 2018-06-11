package net.corda.sandbox.costing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the runtime representation of a running contract.
 *
 * @author ben
 */
public class Contract {

    private static final Logger LOGGER = LoggerFactory.getLogger(Contract.class);

    private final RuntimeCostAccounter accountant = new RuntimeCostAccounter();
    private final Thread contractThread;
    private final Class<?> vettedCode;
    private final ContractExecutor executionStrategy;

    public Contract(final Class<?> newCode, final ContractExecutor strategy) {
        vettedCode = newCode;
        executionStrategy = strategy;
        contractThread = new Thread(() -> executionStrategy.execute(this));
        contractThread.setName("ContractThread-" + System.currentTimeMillis());
        contractThread.setDaemon(true);
    }

    public boolean isViable() {
        return executionStrategy.isSuitable(this);
    }

    public Thread getThread() {
        return contractThread;
    }

    public Class<?> getCode() {
        return vettedCode;
    }

    public void start() {
        contractThread.start();
    }

    void suicide() {
        LOGGER.info("Terminating contract " + this);
        throw new ThreadDeath();
    }
}
