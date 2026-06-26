version = 1

cloudstream {
    language = "bn"
    authors = listOf("Your Name")
    description = "MovieDokan Provider for Movies and TV Series"
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AnimeMovie",
        "Anime",
        "Cartoon"
    )

    iconUrl = "https://moviedokan.co/wp-content/uploads/2025/12/cropped-Logo-1.png"
}
