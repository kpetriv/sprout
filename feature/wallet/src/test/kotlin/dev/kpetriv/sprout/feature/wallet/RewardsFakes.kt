package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.SessionAuthorProvider
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/** In-memory [KidRepository]; set [failing] to make reads throw, [pending] to make them never answer. */
class FakeKidRepository(kids: List<Kid> = emptyList()) : KidRepository {
    val kids = MutableStateFlow(kids)
    var failing = false
    var pending = false

    override fun observeKids(): Flow<List<Kid>> = flow {
        check(!failing) { "storage unavailable" }
        if (pending) awaitCancellation()
        kids.collect { emit(it) }
    }

    override suspend fun getKid(id: KidId): Kid? = kids.value.firstOrNull { it.id == id }

    override suspend fun upsert(kid: Kid) = kids.update { list -> list.filterNot { it.id == kid.id } + kid }

    override suspend fun archive(id: KidId): Boolean = false
}

/** The household's currency, changeable as a pull would change it. */
class FakeFiatCurrencyProvider(initial: Currency.Fiat = TestData.gbp) : FiatCurrencyProvider {
    val currency = MutableStateFlow(initial)

    override fun fiatCurrency(): Currency.Fiat = currency.value

    override suspend fun currentFiatCurrency(): Currency.Fiat = currency.value

    override fun observeFiatCurrency(): Flow<Currency.Fiat> = currency
}

/** A parent signed in to [householdId] on one device. */
class FakeSessionAuthorProvider(private val householdId: HouseholdId = RewardsTestData.household) :
    SessionAuthorProvider {
    override suspend fun currentAuthor(): EventAuthor =
        EventAuthor(householdId, UserId("parent-1"), Role.PARENT, DeviceId("device-1"))
}

object RewardsTestData {
    val household = HouseholdId("household-1")
    val mia = kid("kid-mia", "Mia")
    val leo = kid("kid-leo", "Leo")
    val zoe = kid("kid-zoe", "Zoe")

    fun kid(id: String, name: String) = Kid(
        id = KidId(id),
        householdId = household,
        firstName = name,
        avatarId = "fox",
        birthYearMonth = YearMonth(2017, 3),
        uiTier = KidUiTier.SPROUT,
    )

    @Suppress("LongParameterList") // A reward's fields, with defaults for the usual case.
    fun reward(
        id: String,
        title: String = "Ice cream",
        kind: RewardKind = RewardKind.TREAT,
        cost: Long = 250,
        minutes: Int? = null,
        active: Boolean = true,
        forKids: Set<KidId> = emptySet(),
        householdId: HouseholdId = household,
        currency: Currency = TestData.gbp,
    ) = Reward(RewardId(id), householdId, title, kind, Money(cost), currency, minutes, active, forKids)
}
