package dev.kpetriv.sprout.core.ai

/**
 * What [AiGateway] needs to know about the device it runs on (D-M6-34). The app provides it from the session.
 *
 * On a kid's own linked phone there is no parent area, so nobody there can change [AiPreferences]; in M6 such a phone
 * uses the deterministic rules for every text task (in practice the kindness check, the only one a kid phone runs)
 * whatever its local switch says. A parent-synced "allow on-device AI on kid phones" switch comes with household
 * policy v2 (M7).
 */
fun interface AiContext {
    /**
     * True on a kid's own linked phone (the session's role is KID on a linked device). The router reads it inside each
     * call's budget; when it throws or does not answer in time, the call takes the rules path.
     */
    suspend fun isLinkedKidDevice(): Boolean
}
