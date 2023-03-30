config.resolve.alias = {
    "net": false,
    "util": false,
    "tls": false,
    "crypto": false,
}
module.exports = {
  devServer: {
    headers: {
      "Cross-Origin-Embedder-Policy": "require-corp",
      "Cross-Origin-Opener-Policy": "same-origin",
    },
  },
};