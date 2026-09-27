package com.fourj.iptv.domain.model

/**
 * Where this device appears to be, as far as an IP lookup can tell.
 *
 * Every field except [city] is optional because that is genuinely all the service promises: a city
 * is usually resolvable, a region often is not, and a country code goes missing on the reserved and
 * private ranges a television is often on.
 */
data class Place(
    val city: String,
    val region: String?,
    val country: String?,
    val countryCode: String?,
) {
    /**
     * One line for a television: "Kansas City, United States".
     *
     * Built from the parts that exist rather than padded with placeholders. A row reading
     * "Kansas City, , " tells the viewer the lookup half-failed, which is worse than telling them
     * less.
     */
    val label: String
        get() = listOfNotNull(city, region, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")

    /**
     * The short form, for the top bar: "Kansas City, US".
     *
     * The full [label] is a third longer and there is no room for it. The bar carries a wordmark, up
     * to seven controls and the sign-out strip, and adding a long place name pushed Account off the
     * right edge - the row is wider than the screen, and the weight spacer that separates the two
     * halves collapses to nothing rather than absorbing the overflow.
     *
     * The country code rather than the country name for the same reason: two letters disambiguate
     * "Springfield" and "London" as well as a full country name does, in a fifth of the width.
     */
    val shortLabel: String
        get() = listOfNotNull(city.takeIf { it.isNotBlank() }, countryCode?.takeIf { it.isNotBlank() })
            .joinToString(", ")
}
