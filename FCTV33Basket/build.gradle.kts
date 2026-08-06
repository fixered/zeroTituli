// use an integer for version numbers
version = 2

cloudstream {
    language = "it"
    description = "Basket da FCTV33: Serie A1, Serie A2, NBA e tutti gli altri eventi."
    authors = listOf("fixered")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://statics1.tcxru135mdqf.ru/img/fc/favicon_32.png"
}

dependencies {
    val testImplementation by configurations
    // Le regole delle categorie (BasketLeagues) sono kotlin puro: si provano sulla JVM, senza
    // dispositivo, perché non toccano né Android né gli stub di CloudStream.
    testImplementation("junit:junit:4.13.2")
}
