// use an integer for version numbers
version = 2

cloudstream {
    language = "it"
    description = "Mediaset Infinity: catalogo, dirette TV e serie con le stagioni."
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
        "TvSeries",
        "Movie",
        "Documentary",
        "Cartoon",
        "Live"
    )

    iconUrl = "https://static3.mediasetplay.mediaset.it/static/images/mplay-logo-organization-v2.png"
}

dependencies {
    val testImplementation by configurations
    // I file puri del plugin girano anche fuori da Android: i test stanno sulla JVM,
    // senza dispositivo. Gli stub di CloudStream lì non esistono, ecco perché quei
    // file non li importano.
    testImplementation("junit:junit:4.13.2")
}
