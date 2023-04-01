config.resolve.alias = {
    "net": false,
    "util": false,
    "tls": false,
    "crypto": false,
}
if (config.devServer != null) {
    config.devServer.headers = {
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Embedder-Policy": "require-corp"
    }
}