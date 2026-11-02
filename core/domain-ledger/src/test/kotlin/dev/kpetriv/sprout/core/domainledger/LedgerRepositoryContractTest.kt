package dev.kpetriv.sprout.core.domainledger

/** Runs the [LedgerRepositoryContract] against the in-memory reference implementation. */
class LedgerRepositoryContractTest : LedgerRepositoryContract() {
    override suspend fun createRepository(accounts: List<Account>): LedgerRepository =
        InMemoryLedgerRepository(accounts)
}
