package soy.engindearing.omnitak.mobile.data

/**
 * #212: the single `relay_gateway_enabled` switch (#179) became two direction
 * switches, `relay_to_server_enabled` and `relay_to_mesh_enabled`.
 *
 * A direction whose own key has never been written (everyone upgrading from a
 * build with the single switch) takes the legacy switch's value, so an operator
 * who had the gateway ON keeps both directions ON and one who had it OFF keeps
 * both OFF. [stored] is the direction's own key, [legacyGateway] the old key;
 * either may be absent. Once [UserPrefsStore.update] has run it writes both
 * direction keys, so the legacy key stops mattering.
 *
 * Pure so a JVM unit test can drive it without a DataStore or a Context.
 */
internal fun resolveRelayDirection(stored: Boolean?, legacyGateway: Boolean?): Boolean =
    stored ?: legacyGateway ?: false
