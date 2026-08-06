// use an integer for version numbers
version = 26


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

     description = "Live streams from Hattrick."
    authors = listOf("fixered")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://logowiki.net/wp-content/uploads/imgp/Hattrick-Logo-1-5512.jpg"
}

dependencies {
    val testImplementation by configurations
    // La lettura delle pagine dei player (HattrickPlayers) e i ritocchi al manifest (shared/Mpd)
    // non toccano né rete né Android: i test girano sulla JVM, senza dispositivo.
    testImplementation("junit:junit:4.13.2")
    // Il proxy locale (shared/LocalProxy.kt) gira su thread propri e usa runBlocking: nel plugin
    // le coroutine le fornisce l'app, nei test servono sul percorso di esecuzione.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}
