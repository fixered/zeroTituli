// use an integer for version numbers
version = 2

dependencies {
    // Il proxy HLS locale gira su un thread proprio e deve chiamare le funzioni suspend
    // dell'API: serve runBlocking. Le coroutine le fornisce l'app, quindi non finiscono nel .cs3.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}


cloudstream {
    language = "it"
    description = "Partite delle squadre e delle competizioni principali da FCTV33."
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
