# Instagram for Flare

This Flare API v2 plugin reads the Instagram Following feed, profiles, posts and comments. Profile posts have list and grid views. Posts include photos, carousels and playable video when Instagram supplies a video URL. Opening a post requests fresh media URLs.

## Install and sign in

1. In a Flare client with Plugin API v2 support, open **Settings → Plugins** and import the `.fpp` package.
2. Add an Instagram account using `https://www.instagram.com` or `https://instagram.com`. Use **Sign in to Instagram** to complete sign-in in Flare’s WebView.
3. Use **Import session cookies** if the WebView is unavailable. Paste the `Cookie` header from your own signed-in Instagram session into the secret field. `sessionid` and `ds_user_id` are required. `csrftoken`, `mid`, `ig_did` and `rur` are optional. Treat the header like a password.

The plugin saves the session in Flare’s account credential store. It sends session cookies only to `www.instagram.com` and returns media URLs only from the site and its CDN domains. Open the WebView again if Instagram requires verification. A rate limit pauses requests for that account for the duration sent by Instagram, with a 15-minute default.

## Package and test

Run from the repository root using Node.js 20 or newer.

```sh
node --check plugins/instagram/plugin.js
node --test plugins/instagram.test.mjs
node feature/plugin/sdk/skill/flare-plugin/pack.mjs plugins/instagram build/artifacts/flare-instagram-0.1.0.fpp
```

The mock tests run with an isolated JavaScript host and no network. For authenticated client acceptance, import the package, sign in, refresh and page through Following, open a profile’s list and grid, and open a carousel and a Reel. Check video playback and load the post’s comments.

## Instagram Web requests

All paths below are relative to `https://www.instagram.com`. Instagram can change these private Web interfaces. The paths and Following document ID are centralized near the top of `plugin.js`.

| Flare operation | Web request |
| --- | --- |
| Sign-in verification and profile lookup by ID | `GET /api/v1/users/{id}/info/` using the session account ID during sign-in. For opaque session cookies, `GET /` first checks the signed-in viewer in the page's JSON bootstrap. |
| Profile lookup by handle | `GET /api/v1/users/web_profile_info/?username={handle}`. HTTP 400 can use `GET /api/v1/feed/user/{handle}/username/?count=1` to resolve the owner ID, then read the profile by ID. |
| Profile posts and grid | `GET /api/v1/feed/user/{id}/` with a `max_id` continuation |
| Following | `GET /?variant=following` for the page’s JSON bootstrap tokens, then the read-only `POST /graphql/query` operation `PolarisFeedRootPaginationCachedQuery_subscribe` (`38576618445314797`) with the `following` variant and an `after` continuation |
| Fresh post detail | `GET /api/v1/media/{id}/info/` |
| Top-level comments | `GET /api/v1/media/{id}/comments/` with a `min_id` or `max_id` continuation where present |

The adapter uses published implementation evidence for [account verification and profile lookup](https://github.com/jackwener/OpenCLI/tree/main/clis/instagram), the [Following variant](https://github.com/ajaygandecha/unplugged/blob/c656d7fcb058e6ccd5cc3f749a80e3c91ecb5a9c/Unplugged/Feed/Services/Providers/InstagramProvider.swift), the [persisted feed query](https://github.com/stafawashere/dumpsta-gram/blob/b926f8e3174dd000126a679e30a5b4b526903513/engine/dumpstagram/_private/web/documents/feed.py) and [web media and comment requests](https://github.com/killdano/mirrormsg/blob/031ff8daae8ca14e3b391ecb3f8c85d19ad45f03/combined-go/igbridge/feed_ig.go). Authenticated compatibility must be checked against the running client and Instagram Web session.
