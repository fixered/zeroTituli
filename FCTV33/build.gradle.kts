// use an integer for version numbers
version = 5

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
