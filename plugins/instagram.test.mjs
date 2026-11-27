import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("./instagram/plugin.js", import.meta.url), "utf8");
const manifest = JSON.parse(await readFile(new URL("./instagram/manifest.json", import.meta.url), "utf8"));
const script = new vm.Script(source, { filename: "instagram/plugin.js" });
const ORIGIN = "https://www.instagram.com";
const HOST = "www.instagram.com";
const CREATOR_ID = "9007199254740993";
const POST_ID = "4907199254740993913";
const SESSION = {
  sessionid: `${CREATOR_ID}%3Amock-instagram-session`,
  ds_user_id: CREATOR_ID,
  csrftoken: "mock-csrf",
  rur: "mock-region",
};

// Placeholder identities and URLs replace the original values in the sampled
// media fields. Login, error, duplicate, and missing-caption scenarios are synthetic.
const CREATOR = {
  pk: CREATOR_ID,
  id: CREATOR_ID,
  username: "example_creator",
  full_name: "Example Creator",
  profile_pic_url: "https://scontent-test.cdninstagram.com/mock-avatar.jpg",
  biography: "Sample account for Flare",
  follower_count: 23,
  following_count: 12,
  media_count: 3,
  is_verified: true,
  is_private: false,
};

const COVER = "https://scontent-test.cdninstagram.com/mock-cover.jpg";
const ORIGINAL = "https://scontent-test.cdninstagram.com/mock-photo.jpg";
const THUMBNAIL = "https://scontent-test.cdninstagram.com/mock-thumbnail.jpg";
const VIDEO = "https://video-test.fbcdn.net/mock-clip.mp4";

// Reduced, redacted projections of the image-1, reel-video-1, and
// mixed-carousel-1 additionalData.json captures at this revision:
// https://github.com/flurrux/insta-loader/tree/3562746231bebfe0b328a688bd21f44d7f1769a8/dev/reference-data
function photo(id = POST_ID, overrides = {}) {
  return {
    pk: id,
    id: `${id}_${CREATOR_ID}`,
    code: "ExamplePhoto",
    user: CREATOR,
    taken_at: 1_700_000_000,
    media_type: 1,
    product_type: "feed",
    caption: { text: "<literal caption>\nSecond line" },
    image_versions2: {
      candidates: [
        { url: THUMBNAIL, width: 320, height: 240 },
        { url: ORIGINAL, width: 1080, height: 810 },
      ],
    },
    accessibility_caption: "A sample photograph",
    like_count: 15,
    comment_count: 4,
    ...overrides,
  };
}

function video(id, overrides = {}) {
  return photo(id, {
    code: "ExampleReel",
    media_type: 2,
    product_type: "clips",
    video_duration: 2.375,
    video_versions: [
      { url: "https://video-test.fbcdn.net/mock-clip-low.mp4", width: 360, height: 640 },
      { url: VIDEO, width: 1080, height: 1920 },
    ],
    image_versions2: { candidates: [{ url: COVER, width: 1080, height: 1920 }] },
    ...overrides,
  });
}

function carousel(id) {
  const image = photo("444");
  const clip = video("555");
  return photo(id, {
    code: "ExampleCarousel",
    media_type: 8,
    product_type: "carousel_container",
    carousel_media: [
      { pk: image.pk, id: image.id, media_type: image.media_type, image_versions2: image.image_versions2 },
      { pk: clip.pk, id: clip.id, media_type: clip.media_type, image_versions2: clip.image_versions2, video_versions: clip.video_versions },
    ],
  });
}

function json(contents, options = {}) {
  return {
    status: 200,
    headers: { "content-type": ["application/json"] },
    body: JSON.stringify(contents),
    ...options,
  };
}

function clone(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function harness(responses = [], options = {}) {
  const requests = [];
  const bootstrapRequests = [];
  const storage = new Map();
  const credentialWrites = [];
  let credential = { cookies: { ...SESSION } };
  let credentialRevision = 1;
  let now = 1_700_000_000_000;

  const defaultContext = {
    pluginId: manifest.id,
    platformId: manifest.platform.id,
    packageHash: "mock-package-hash",
    scope: "account",
    origin: ORIGIN,
    accountId: CREATOR_ID,
    locale: "en",
    approvedOrigins: [ORIGIN, "https://instagram.com"],
    credentialAvailable: true,
    assetHandles: [],
  };

  const flare = {
    http: {
      async request(value) {
        const request = clone(value);
        const url = new URL(request.url);
        assert.equal(url.origin, ORIGIN);
        if (/^\/graphql\/query\/?$/.test(url.pathname) && request.method === "POST") {
          assert.equal(request.body.type, "form");
        } else {
          assert.equal(request.method ?? "GET", "GET");
          assert.equal(request.body, undefined);
        }
        assert.equal(request.authorization, undefined);
        assert.ok(Object.keys(request.headers ?? {}).every((name) => !["authorization", "cookie"].includes(name.toLowerCase())));
        if (url.pathname === "/" && url.searchParams.get("variant") === "following") {
          bootstrapRequests.push(request);
          return {
            url: request.url,
            status: 200,
            headers: { "content-type": ["text/html"] },
            body: '<script type="application/json">{"require":[["ScheduledServerJS","handle",null,[{"define":[["DTSGInitialData",[],{"token":"mock-dtsg"},258],["LSD",[],{"token":"mock-lsd"},323]]}]]]}</script>',
          };
        }
        const index = requests.length;
        requests.push(request);
        assert.ok(index < responses.length, `Unexpected request to ${url.pathname}`);
        const next = responses[index];
        const response = typeof next === "function" ? await next(request, url) : next;
        return clone({ url: request.url, ...response });
      },
    },
    credential: {
      async read(readOptions) {
        const value = clone(credential);
        return readOptions?.withRevision ? { value, revision: String(credentialRevision) } : value;
      },
      async replace(next, writeOptions) {
        const expectedRevision = writeOptions?.expectedRevision;
        credentialWrites.push({ value: clone(next), expectedRevision });
        if (options.onCredentialReplace) {
          const updated = options.onCredentialReplace(clone(credential), credentialWrites.length);
          if (updated) {
            credential = updated;
            credentialRevision++;
          }
        }
        if (expectedRevision !== undefined && expectedRevision !== String(credentialRevision)) return false;
        credential = clone(next);
        credentialRevision++;
        return true;
      },
    },
    storage: {
      open(namespace) {
        if (!storage.has(namespace)) storage.set(namespace, { values: {}, revision: 1 });
        const snapshot = storage.get(namespace);
        return {
          async read(keys) {
            const values = keys === undefined
              ? snapshot.values
              : Object.fromEntries(Array.from(keys).filter((key) => Object.hasOwn(snapshot.values, key)).map((key) => [key, snapshot.values[key]]));
            return { values: clone(values), revision: String(snapshot.revision) };
          },
          async write(changes, writeOptions) {
            if (writeOptions?.expectedRevision !== undefined && writeOptions.expectedRevision !== String(snapshot.revision)) {
              throw Object.assign(new Error("Storage revision conflict"), { code: "storage.conflict" });
            }
            Object.assign(snapshot.values, clone(changes.set));
            for (const key of changes.remove ?? []) delete snapshot.values[key];
            snapshot.revision++;
            return { revision: String(snapshot.revision) };
          },
        };
      },
    },
    locale: { current: async () => "en" },
    error(details) {
      return Object.assign(new Error(details.message.fallback ?? details.message.value), clone(details));
    },
  };

  function instantiate(context = defaultContext) {
    let plugin;
    class MockDate extends Date {
      constructor(...args) { super(...(args.length ? args : [now])); }
      static now() { return now; }
    }
    script.runInNewContext({
      Date: MockDate,
      defineFlareSocialPlugin(value) {
        assert.equal(plugin, undefined, "The runtime must register one social plugin");
        plugin = value;
      },
      flare: context.scope === "account" ? flare : { ...flare, credential: undefined },
    });
    assert.ok(plugin);
    return plugin;
  }

  return {
    instantiate,
    requests,
    bootstrapRequests,
    storage,
    credentialWrites,
    credential: () => clone(credential),
    setCredential(cookies) {
      credential = { cookies: clone(cookies) };
      credentialRevision++;
    },
    advance(seconds) { now += seconds * 1000; },
    async invoke(path, request, overrides = {}) {
      const context = Object.freeze({ ...defaultContext, ...overrides });
      let operation = instantiate(context);
      for (const segment of path.split(".")) operation = operation[segment];
      return clone(await operation(request, context));
    },
  };
}

function page(direction = "refresh", cursor) {
  return { direction, cursor, limit: 25, parameters: {} };
}

function home(h, pageRequest = page(), context) {
  return h.invoke("capabilities.timeline.page", { timelineId: "home", page: pageRequest, parameters: {} }, context);
}

function loginRequest(methodId, values = {}, origin = ORIGIN) {
  return { methodId, origin, flowId: "mock-flow-id", values };
}

function loginContext(origin = ORIGIN) {
  return { scope: "login", origin, accountId: undefined, credentialAvailable: false };
}

function accountKey(id, host = HOST) {
  return { id, host };
}

function following(nodes, cursor) {
  // The connection and edge fields are from this public, redacted web capture:
  // https://github.com/stafawashere/dumpsta-gram/blob/b926f8e3174dd000126a679e30a5b4b526903513/engine/tests/fixtures/post_depth/home_timeline_posts.json
  return {
    data: {
      xdt_api__v1__feed__timeline__connection: {
        pagination_source: "following",
        edges: nodes.map((node) => ({ node: { ad: null, media: null, ...node } })),
        page_info: { has_next_page: cursor !== undefined, end_cursor: cursor ?? null },
      },
    },
  };
}

function expectPosts(result, ids) {
  assert.deepEqual(result.items.map(({ type, post }) => {
    assert.equal(type, "post");
    return post.key.id;
  }), ids);
}

test("detector recognizes both supported HTTPS origins and preserves the selected origin", async () => {
  const h = harness();
  for (const origin of [ORIGIN, "https://instagram.com"]) {
    const detected = await h.invoke("detector.detect", { origin }, { scope: "detector", origin });
    assert.equal(detected.match, "exact");
    assert.equal(detected.canonicalOrigin, origin);
  }

  for (const origin of ["https://instagram.com.example.org", "https://m.instagram.com", "https://www.instagram.com:444"]) {
    const detected = await h.invoke("detector.detect", { origin }, { scope: "detector", origin });
    assert.equal(detected.match, "none");
  }
  assert.equal(h.requests.length, 0);
});

test("account renegotiation exposes only the declared read capabilities", async () => {
  const h = harness();
  const plugin = h.instantiate();
  const previous = { capabilities: { "flare.datasource.compose/v2": ["publish"] } };
  const expected = {
    "flare.datasource.account-capabilities/v1": ["negotiate"],
    "flare.datasource.timeline/v1": ["page"],
    "flare.datasource.profile/v1": ["byHandle", "byId", "timeline"],
    "flare.datasource.post/v1": ["context", "detail"],
  };
  const result = await h.invoke("capabilities.accountCapabilities.negotiate", { previous });
  assert.deepEqual(Object.fromEntries(Object.entries(result.capabilities).map(([key, operations]) => [key, operations.sort()])), expected);
  for (const [key, operations] of Object.entries(expected)) {
    assert.deepEqual(Object.keys(manifest.platform.capabilities[key].operations).sort(), operations);
  }
  assert.equal(result.composeConfig, undefined);
  assert.equal(plugin.capabilities.compose, undefined);
  assert.equal(plugin.capabilities.directMessage, undefined);
  assert.equal(plugin.capabilities.relation, undefined);
  assert.equal(plugin.capabilities.post.mutate, undefined);
  assert.equal(plugin.capabilities.post.delete, undefined);
});

test("WebView login waits for required cookies and verifies the selected account", async () => {
  const h = harness([json({ status: "ok", user: CREATOR })]);
  const origin = "https://instagram.com";
  const context = loginContext(origin);
  const request = loginRequest("browser", {}, origin);
  const browserMethod = manifest.platform.loginMethods.find((method) => method.id === "browser");
  const start = await h.invoke("login.browser.begin", request, context);
  assert.deepEqual(start, { type: "webCookie", startUrl: `${ORIGIN}/accounts/login/` });
  assert.equal(start.startUrl, browserMethod.cookie.startUrl);

  const sourceUrl = `${ORIGIN}/`;
  const entries = [
    { sourceUrl, name: "sessionid", value: SESSION.sessionid },
    { sourceUrl: "https://instagram.com/", name: "ds_user_id", value: CREATOR_ID },
    { sourceUrl, name: "other", value: "not-a-supported-cookie" },
  ];
  const check = (cookies) => h.invoke("login.browser.check", { ...request, cookies: { cookies } }, context);
  assert.equal((await check(entries)).type, "pending");
  assert.equal(h.requests.length, 0);

  const success = await check([...entries, { sourceUrl, name: "ds_user_id", value: CREATOR_ID }]);
  assert.equal(success.type, "success");
  assert.equal(success.value.origin, origin);
  assert.equal(success.value.accountId, CREATOR_ID);
  assert.deepEqual(success.value.profile.key, accountKey(CREATOR_ID, "instagram.com"));
  assert.equal(success.value.profile.handle, CREATOR.username);
  assert.equal(success.value.profile.displayName, CREATOR.full_name);
  for (const [capability, operations] of Object.entries(success.value.capabilities)) {
    assert.deepEqual(operations.toSorted(), Object.keys(manifest.platform.capabilities[capability].operations).sort());
  }
  const supported = { sessionid: SESSION.sessionid, ds_user_id: CREATOR_ID };
  assert.deepEqual(success.value.credential, { cookies: supported });
  assert.deepEqual(Object.fromEntries(h.requests[0].cookies.map(({ name, value }) => [name, value])), supported);
});

test("manual Cookie import keeps supported fields and rejects an account mismatch", async () => {
  const h = harness([json({ status: "ok", user: CREATOR }), json({ status: "ok", user: { ...CREATOR, pk: "73", id: "73" } })]);
  const context = loginContext();
  const cookie = `Cookie: sessionid=${SESSION.sessionid}; ds_user_id=${CREATOR_ID}; csrftoken=${SESSION.csrftoken}; unwanted=mock-extra`;
  const login = (input) => h.invoke("login.importCookies.begin", loginRequest("importCookies", { cookie: input }), context);
  await assert.rejects(login(`sessionid=${SESSION.sessionid}`), { code: "Validation" });
  await assert.rejects(login(`sessionid=73%3Amock-session; ds_user_id=${CREATOR_ID}`), { code: "Validation" });
  assert.equal(h.requests.length, 0);

  const success = await login(cookie);
  assert.equal(success.type, "success");
  assert.equal(success.value.accountId, CREATOR_ID);
  assert.deepEqual(success.value.credential, {
    cookies: { sessionid: SESSION.sessionid, ds_user_id: CREATOR_ID, csrftoken: SESSION.csrftoken },
  });
  await assert.rejects(login(cookie), { code: "AuthenticationRequired" });
  assert.equal(h.requests.length, 2);
});

test("opaque Cookie import verifies the web viewer independently of the profile lookup", async () => {
  const viewer = (id) => (_, url) => {
    assert.equal(url.pathname, "/");
    return {
      status: 200,
      headers: { "content-type": ["text/html"] },
      body: `<script type="application/json">${JSON.stringify({ require: [["PolarisViewer", [], { data: { id } }]] })}</script>`,
    };
  };
  const profile = (_, url) => {
    assert.equal(url.pathname, `/api/v1/users/${CREATOR_ID}/info/`);
    return json({ status: "ok", user: CREATOR });
  };
  const cookie = `sessionid=mock-opaque-session; ds_user_id=${CREATOR_ID}`;
  const login = (h) => h.invoke("login.importCookies.begin", loginRequest("importCookies", { cookie }), loginContext());
  const matched = harness([viewer(CREATOR_ID), profile]);
  const success = await login(matched);
  assert.equal(success.type, "success");
  assert.equal(success.value.accountId, CREATOR_ID);
  assert.equal(matched.requests.length, 2);

  const mismatched = harness([viewer("73")]);
  await assert.rejects(login(mismatched), { code: "AuthenticationRequired" });
  assert.equal(mismatched.requests.length, 1);
});

test("a rejected WebView flow stops polling until a new sign-in verifies the session", async () => {
  const h = harness([
    json({ status: "fail", message: "login_required" }, { status: 401 }),
    json({ status: "ok", user: CREATOR }),
    json(following([])),
  ]);
  const context = loginContext();
  const request = {
    ...loginRequest("browser"),
    cookies: { cookies: Object.entries(SESSION).map(([name, value]) => ({ sourceUrl: `${ORIGIN}/`, name, value })) },
  };
  const check = (value) => h.invoke("login.browser.check", value, context);
  await assert.rejects(check(request), { code: "AuthenticationRequired" });
  await assert.rejects(check(request), { code: "AuthenticationRequired" });
  assert.equal(h.requests.length, 1);

  const renewed = await check({ ...request, flowId: "new-mock-flow-id" });
  assert.equal(renewed.type, "success");
  assert.equal(renewed.value.accountId, CREATOR_ID);
  assert.equal((await home(h)).endReached, true);
  assert.equal(h.requests.length, 3);
});

test("Following maps photos, Reels, and ordered mixed carousels from a JSON response", async () => {
  const emptyCaptionId = "4907199254740993916";
  const followingResponse = json(following([
    { media: photo(POST_ID, { id: undefined, user: { ...CREATOR, id: undefined } }) },
    { media: carousel("4907199254740993914") },
    { media: video("4907199254740993915", { user: { ...CREATOR, is_private: true } }) },
    { media: photo(emptyCaptionId, { caption: null }) },
  ]));
  followingResponse.body = followingResponse.body
    .replace(`"pk":"${POST_ID}"`, `"pk":${POST_ID}`)
    .replace(`"pk":"${CREATOR_ID}"`, `"pk":${CREATOR_ID}`);
  const h = harness([followingResponse]);
  const result = await home(h);
  assert.equal(result.endReached, true);
  expectPosts(result, [POST_ID, "4907199254740993914", "4907199254740993915", emptyCaptionId]);

  const first = result.items[0].post;
  assert.deepEqual(first.key, accountKey(POST_ID));
  assert.deepEqual(first.author.key, accountKey(CREATOR_ID));
  assert.equal(first.createdAt, "2023-11-14T22:13:20.000Z");
  assert.equal(first.content.value, "<literal caption>\nSecond line");
  assert.equal(first.content.format ?? "plain", "plain");
  assert.equal(first.url, `${ORIGIN}/p/ExamplePhoto/`);
  assert.equal(first.favouritesCount, 15);
  assert.equal(first.repliesCount, 4);
  assert.equal(first.visibility, "public");
  assert.ok(first.actions === undefined || first.actions.length === 0);
  assert.equal(first.media.length, 1);
  assert.deepEqual(
    [first.media[0].type, first.media[0].url, first.media[0].previewUrl, first.media[0].width, first.media[0].height, first.media[0].description],
    ["image", ORIGINAL, THUMBNAIL, 1080, 810, "A sample photograph"],
  );

  assert.deepEqual(result.items[1].post.media.map(({ type }) => type), ["image", "video"]);
  const reel = result.items[2].post;
  assert.equal(reel.visibility, "followers");
  assert.equal(reel.author.locked, true);
  assert.equal(reel.media.length, 1);
  assert.deepEqual(
    [reel.media[0].type, reel.media[0].url, reel.media[0].previewUrl, reel.media[0].width, reel.media[0].height, reel.media[0].durationMillis],
    ["video", VIDEO, COVER, 1080, 1920, 2375],
  );
  assert.equal(result.items[3].post.content.value, "");
});

test("Following filters promotions and stops on a repeated or empty page", async () => {
  const previousId = "4907199254740993918";
  const nextId = "4907199254740993919";
  const cursor = "mock-cursor-with-/=_+·";
  const h = harness([
    json(following([
      { media: photo(previousId) },
      { media: photo(previousId) },
      { media: photo("497"), ad: { id: "mock-ad" } },
      { media: photo("498", { ad_id: "sample-ad" }) },
      { media: photo("499"), injected: { id: "sample-injected-node" } },
      { suggested_users: { suggestions: [] } },
      { clips_netego: {} },
    ], cursor)),
    json(following([{ media: photo(previousId) }, { media: photo(nextId) }], cursor)),
    json(following([], "unused-empty-page-cursor")),
  ]);
  const first = await home(h);
  expectPosts(first, [previousId]);
  assert.ok(first.olderCursor);
  assert.notEqual(first.endReached, true);
  const second = await home(h, page("older", first.olderCursor));
  expectPosts(second, [nextId]);
  assert.equal(second.endReached, true);
  assert.equal(second.olderCursor, undefined);
  const third = await home(h);
  assert.equal(third.endReached, true);
  assert.deepEqual(third.items ?? [], []);
  assert.equal(third.olderCursor, undefined);
  assert.equal(h.requests.length, 3);
  const firstVariables = JSON.parse(h.requests[0].body.values.variables);
  const nextVariables = JSON.parse(h.requests[1].body.values.variables);
  assert.equal(firstVariables.variant, "following");
  assert.equal(firstVariables.data.pagination_source, "following");
  assert.equal(nextVariables.variant, "following");
  assert.equal(nextVariables.after, cursor);
});

test("profile lookup maps REST and web profile response fields", async () => {
  const h = harness([
    (_, url) => {
      assert.equal(url.pathname, `/api/v1/users/${CREATOR_ID}/info/`);
      return json({ status: "ok", user: CREATOR });
    },
    (_, url) => {
      assert.equal(url.pathname, "/api/v1/users/web_profile_info/");
      assert.equal(url.searchParams.get("username"), CREATOR.username);
      return json({ status: "ok", data: { user: {
        id: CREATOR_ID,
        username: CREATOR.username,
        full_name: CREATOR.full_name,
        profile_pic_url: CREATOR.profile_pic_url,
        biography: CREATOR.biography,
        is_verified: true,
        is_private: false,
        edge_followed_by: { count: 23 },
        edge_follow: { count: 12 },
        edge_owner_to_timeline_media: { count: 3 },
      } } });
    },
  ]);
  const fromId = await h.invoke("capabilities.profile.byId", { key: accountKey(CREATOR_ID) });
  const fromHandle = await h.invoke("capabilities.profile.byHandle", { handle: CREATOR.username, host: HOST });
  for (const profile of [fromId, fromHandle]) {
    assert.deepEqual(profile.key, accountKey(CREATOR_ID));
    assert.equal(profile.handle, CREATOR.username);
    assert.equal(profile.displayName, CREATOR.full_name);
    assert.equal(profile.description.value, CREATOR.biography);
    assert.equal(profile.avatarUrl, CREATOR.profile_pic_url);
    assert.equal(profile.url, `${ORIGIN}/${CREATOR.username}/`);
    assert.equal(profile.followersCount, 23);
    assert.equal(profile.followingCount, 12);
    assert.equal(profile.postsCount, 3);
    assert.equal(profile.verified, true);
    assert.equal(profile.locked, false);
  }
});

test("profile posts and gallery use an opaque continuation for older pages", async () => {
  const remoteCursor = "mock-profile+/=cursor";
  const h = harness([
    (_, url) => {
      assert.equal(url.pathname, `/api/v1/feed/user/${CREATOR_ID}/`);
      assert.equal(url.searchParams.get("max_id"), null);
      return json({ status: "ok", items: [photo()], next_max_id: remoteCursor, more_available: true });
    },
    (_, url) => {
      assert.equal(url.pathname, `/api/v1/feed/user/${CREATOR_ID}/`);
      assert.equal(url.searchParams.get("max_id"), remoteCursor);
      return json({ status: "ok", items: [video("4582")], more_available: false });
    },
    json({ status: "ok", items: [photo()], more_available: false }),
  ]);
  const request = { profile: accountKey(CREATOR_ID), tabId: "posts", parameters: {} };
  const timeline = (options = {}) => h.invoke("capabilities.profile.timeline", { ...request, page: page(), ...options });
  const first = await timeline();
  expectPosts(first, [POST_ID]);
  assert.ok(first.olderCursor);
  const second = await timeline({ page: page("older", first.olderCursor) });
  expectPosts(second, ["4582"]);
  assert.equal(second.endReached, true);
  const gallery = await timeline({ tabId: "gallery" });
  expectPosts(gallery, [POST_ID]);
});

test("post detail re-fetches current signed media URLs", async () => {
  const response = (signedUrl) => (_, url) => {
    assert.equal(url.pathname, `/api/v1/media/${POST_ID}/info/`);
    return json({ status: "ok", items: [photo(POST_ID, {
      image_versions2: { candidates: [{ url: signedUrl, width: 1080, height: 810 }] },
    })] });
  };
  const oldUrl = `${ORIGINAL}?oe=mock-old`;
  const newUrl = `${ORIGINAL}?oe=mock-new`;
  const h = harness([response(oldUrl), response(newUrl)]);
  const detail = () => h.invoke("capabilities.post.detail", { key: accountKey(POST_ID) });
  const before = await detail();
  const after = await detail();
  assert.equal(before.type, undefined);
  assert.equal(after.type, undefined);
  assert.deepEqual(after.post.key, accountKey(POST_ID));
  assert.equal(before.post.media[0].url, oldUrl);
  assert.equal(after.post.media[0].url, newUrl);
  assert.equal(h.requests.length, 2);
});

test("post context anchors fresh results and paginates older comments", async () => {
  const cursor = "mock-comment-cursor";
  const comment = {
    pk: "6789555135442225131",
    user: CREATOR,
    text: "First comment",
    created_at: 1_700_000_060,
    comment_like_count: 1,
  };
  const h = harness([
    (_, url) => {
      assert.equal(url.pathname, `/api/v1/media/${POST_ID}/info/`);
      return json({ status: "ok", items: [photo()] });
    },
    (_, url) => {
      assert.equal(url.pathname, `/api/v1/media/${POST_ID}/comments/`);
      assert.equal(url.searchParams.get("can_support_threading"), "true");
      return json({ status: "ok", comments: [comment], next_min_id: cursor, has_more_headload_comments: true });
    },
    (_, url) => {
      assert.equal(url.pathname, `/api/v1/media/${POST_ID}/comments/`);
      assert.ok([url.searchParams.get("min_id"), url.searchParams.get("max_id")].includes(cursor));
      return json({ status: "ok", comments: [{ ...comment, pk: "6789555135442225132", text: "Older comment" }] });
    },
  ]);
  const context = (request) => h.invoke("capabilities.post.context", { key: accountKey(POST_ID), page: request });
  const fresh = await context(page());
  assert.equal(fresh.items.length, 2);
  assert.deepEqual(fresh.items[0].post.key, accountKey(POST_ID));
  const reply = fresh.items[1].post;
  assert.notEqual(reply.key.id, POST_ID);
  assert.equal(reply.content.value, comment.text);
  assert.deepEqual(reply.replyTo, accountKey(POST_ID));
  assert.equal(reply.createdAt, "2023-11-14T22:14:20.000Z");
  assert.ok(fresh.olderCursor);
  const older = await context(page("older", fresh.olderCursor));
  assert.equal(older.items.length, 1);
  assert.equal(older.items[0].post.content.value, "Older comment");
  assert.notEqual(older.items[0].post.key.id, reply.key.id);
  assert.equal(older.endReached, true);
  assert.equal(h.requests.length, 3);
});

for (const failure of [
  { label: "401", response: json({ status: "fail", message: "unknown" }, { status: 401 }) },
  { label: "403", response: json({ status: "fail", message: "unknown" }, { status: 403 }) },
  { label: "login_required", response: json({ status: "fail", message: "login_required" }) },
  { label: "challenge_required", response: json({ status: "fail", message: "challenge_required" }) },
  { label: "checkpoint_required", response: json({ status: "fail", message: "checkpoint_required" }) },
  { label: "checkpoint redirect", response: json({}, { status: 302, redirectUrl: ORIGIN + "/checkpoint/" }) },
]) {
  test(`${failure.label} blocks subsequent requests from the affected account`, async () => {
    const h = harness([failure.response, json(following([]))]);
    await assert.rejects(home(h), { code: "AuthenticationRequired" });
    await assert.rejects(home(h), { code: "AuthenticationRequired" });
    assert.equal(h.requests.length, 1);
    assert.equal(h.bootstrapRequests.length, 1);

    const saved = h.storage.get("ig_circuit_breaker");
    assert.ok(Object.keys(saved.values).length > 0);
    assert.ok(!JSON.stringify(saved.values).includes(SESSION.sessionid));

    const otherAccountId = "9007199254740997";
    h.setCredential({ ...SESSION, sessionid: `${otherAccountId}%3Aseparate-mock-session`, ds_user_id: otherAccountId });
    const other = await home(h, page(), { accountId: otherAccountId });
    assert.equal(other.endReached, true);
    assert.equal(h.requests.length, 2);
  });
}

test("429 persists a cooldown and allows a request after expiry", async () => {
  const h = harness([
    json({ status: "fail", message: "rate_limited" }, { status: 429, headers: { "retry-after": ["1800"] } }),
    json(following([])),
  ]);
  let retryInSeconds;
  await assert.rejects(home(h), (error) => {
    assert.equal(error.code, "RateLimited");
    retryInSeconds = error.retryAfterSeconds;
    assert.ok(Number.isInteger(retryInSeconds) && retryInSeconds > 1);
    return true;
  });
  assert.ok(Object.keys(h.storage.get("ig_circuit_breaker").values).length > 0);
  h.advance(retryInSeconds - 1);
  await assert.rejects(home(h), { code: "RateLimited" });
  assert.equal(h.requests.length, 1);
  assert.equal(h.bootstrapRequests.length, 1);
  h.advance(2);
  assert.equal((await home(h)).endReached, true);
  assert.equal(h.requests.length, 2);
});

test("Set-Cookie renews the allowlisted values through a revision-checked replacement", async () => {
  const response = json(following([]), {
    headers: {
      "set-cookie": [
        "csrftoken=renewed-csrf; Expires=Tue, 19 Nov 2030 01:02:03 GMT; Path=/; Secure",
        'rur="renewed-region"; Path=/; Secure',
        "sessionid=must-retain-existing-session; Path=/; Secure",
        "tracking=ignored; Path=/; Secure",
      ],
    },
  });
  const h = harness([response, json(following([]))]);
  await home(h);
  assert.deepEqual(h.credentialWrites.map(({ expectedRevision }) => expectedRevision), ["1"]);
  const cookies = h.credential().cookies;
  assert.equal(cookies.csrftoken, "renewed-csrf");
  assert.equal(cookies.rur.replaceAll('"', ""), "renewed-region");
  assert.equal(cookies.sessionid, SESSION.sessionid);
  assert.equal(cookies.tracking, undefined);
  await home(h);
  assert.equal(h.requests[1].cookies.find(({ name }) => name === "csrftoken").value, "renewed-csrf");
});

test("credential conflicts preserve a newer signed-in session without replaying the HTTP call", async () => {
  const h = harness([
    json(following([]), { headers: { "set-cookie": ["csrftoken=stale-csrf; Path=/; Secure"] } }),
  ], {
    onCredentialReplace(credential, count) {
      if (count !== 1) return undefined;
      credential.cookies.sessionid = `${CREATOR_ID}%3Anew-mock-session`;
      credential.cookies.csrftoken = "new-mock-csrf";
      return credential;
    },
  });
  await home(h);
  assert.equal(h.credential().cookies.sessionid, `${CREATOR_ID}%3Anew-mock-session`);
  assert.equal(h.credential().cookies.csrftoken, "new-mock-csrf");
  assert.equal(h.requests.length, 1);
});

test("HTML and a structurally invalid JSON feed surface InvalidResponse", async () => {
  const h = harness([
    { status: 200, headers: { "content-type": ["text/html"] }, body: "<!doctype html><title>Log in</title>" },
    json({ status: "ok", data: {} }),
  ]);
  await assert.rejects(home(h), { code: "InvalidResponse" });
  await assert.rejects(home(h), { code: "InvalidResponse" });
  assert.equal(h.requests.length, 2);
});
