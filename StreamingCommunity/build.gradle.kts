// use an integer for version numbers
version = 2

cloudstream {
    language = "it"
    description = "Film e serie da StreamingCommunity, con il dominio corrente trovato da solo."
    // Adattamento del plugin di doGior (github.com/doGior/doGiorsHadEnough).
    authors = listOf("doGior", "fixered")

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
        "Cartoon"
    )

    requiresResources = true

    iconUrl = "https://streamingunity.cc/apple-touch-icon.png?v=2"
}

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    val implementation by configurations
    implementation("com.google.android.material:material:1.12.0")
}
