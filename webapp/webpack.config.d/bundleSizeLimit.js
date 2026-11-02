// The bundle size webpack warns past. Its default, 244 KiB, is meant for a site's first visit on a slow connection;
// this app is one bundle (Kotlin/JS compiles it as one module) of about 3.3 MiB, fetched once and then cached, which
// is a small download today. So the limit is 5 MiB: a warning now means the bundle has grown by half, and is the
// time to look at what grew, or at splitting the bundle so a page loads only what it uses. A development build
// (-Pwebapp.dev=true) is far larger, and webpack does not check its size.
// Kotlin/JS merges every file in webpack.config.d into the generated config.
config.performance = Object.assign(config.performance || {}, {
    maxAssetSize: 5 * 1024 * 1024,
    maxEntrypointSize: 5 * 1024 * 1024,
});
