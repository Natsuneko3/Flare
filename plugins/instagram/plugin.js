(function () {
  "use strict";

  const ORIGIN = "https://www.instagram.com";
  const APEX_ORIGIN = "https://instagram.com";
  const LOGIN_URL = ORIGIN + "/accounts/login/";
  const COOKIE_PROBE = ORIGIN + "/";
  const COOKIE_NAMES = ["sessionid", "ds_user_id", "csrftoken", "mid", "ig_did", "rur"];
  const ROTATING_COOKIES = ["csrftoken", "rur"];
  const WEB_APP_ID = "936619743392459";
  const USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
  const COOLDOWN_SECONDS = 15 * 60;
  const MAX_RETRY_SECONDS = 7 * 24 * 60 * 60;
  const MAX_CURSOR_LENGTH = 4096;
  const FOLLOWING_QUERY = {
    page: "/?variant=following",
    path: "/graphql/query",
    name: "PolarisFeedRootPaginationCachedQuery_subscribe",
    documentId: "38576618445314797",
    connection: "xdt_api__v1__feed__timeline__connection",
  };
  const ENDPOINTS = {
    profileById: "/api/v1/users/{id}/info/",
    profileByHandle: "/api/v1/users/web_profile_info/",
    profileByHandleFeed: "/api/v1/feed/user/{id}/username/",
    profilePosts: "/api/v1/feed/user/{id}/",
    post: "/api/v1/media/{id}/info/",
    comments: "/api/v1/media/{id}/comments/",
  };
  let followingSession;

  const ERRORS = {
    AuthenticationRequired: ["authenticationRequired", "Sign in again in the Instagram browser and complete any verification there."],
    RateLimited: ["rateLimited", "Instagram is limiting requests. Try again after the cooldown."],
    InvalidResponse: ["invalidResponse", "Instagram returned an unexpected response."],
    Validation: ["invalidCookies", "Import a Cookie header containing a valid sessionid and ds_user_id."],
    NotFound: ["notFound", "The Instagram account or post could not be found."],
    Remote: ["remote", "Instagram could not complete the request."],
    Unsupported: ["unsupported", "This Instagram operation is unavailable."],
    Network: ["network", "Could not reach Instagram."],
  };

  function pluginError(code, options) {
    const [key, fallback] = ERRORS[code];
    return flare.error({ code, message: { key: "error." + key, fallback }, ...options });
  }

  function capabilities() {
    return {
      "flare.datasource.account-capabilities/v1": ["negotiate"],
      "flare.datasource.timeline/v1": ["page"],
      "flare.datasource.profile/v1": ["byId", "byHandle", "timeline"],
      "flare.datasource.post/v1": ["detail", "context"],
    };
  }

  function detect(request) {
    const match = request.origin === ORIGIN || request.origin === APEX_ORIGIN;
    return match
      ? { match: "exact", canonicalOrigin: request.origin, software: "Instagram" }
      : { match: "none", canonicalOrigin: request.origin };
  }

  function requireInstagram(context) {
    if (context.origin !== ORIGIN && context.origin !== APEX_ORIGIN) {
      throw pluginError("Unsupported");
    }
  }

  function entityKey(id, context) {
    return { id, host: context.origin.slice("https://".length) };
  }

  function decimalId(value) {
    if (typeof value === "number" && Number.isSafeInteger(value)) value = String(value);
    return typeof value === "string" && /^[1-9]\d{0,29}$/.test(value) ? value : null;
  }

  function username(value) {
    return typeof value === "string" && /^[A-Za-z0-9_.]{1,30}$/.test(value) && !/^\.+$/.test(value)
      ? value
      : null;
  }

  function object(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }

  function normalizeCookie(value) {
    if (typeof value !== "string") return null;
    const unquoted = value.startsWith('"') && value.endsWith('"') ? value.slice(1, -1) : value;
    return unquoted.length <= 4096 && /^[!#-+\--:<-~]+$/.test(unquoted) ? unquoted : null;
  }

  function cookieOwner(sessionId) {
    return /^(\d+)(?::|%3a)/i.exec(sessionId)?.[1];
  }

  function sessionCookies(input) {
    const cookies = {};
    for (const name of COOKIE_NAMES) {
      const value = normalizeCookie(input[name]);
      if (value !== null) cookies[name] = value;
    }
    if (!cookies.sessionid || !decimalId(cookies.ds_user_id)) return null;
    // The standard Instagram session Cookie contains the issuing user's ID.
    const owner = cookieOwner(cookies.sessionid);
    return !owner || owner === cookies.ds_user_id ? cookies : null;
  }

  function importedCookies(value) {
    if (typeof value !== "string" || value.length > 16384 || /[\r\n\0]/.test(value)) {
      throw pluginError("Validation");
    }
    const fields = {};
    for (const pair of value.replace(/^\s*Cookie:\s*/i, "").split(";")) {
      const equals = pair.indexOf("=");
      if (equals < 1) continue;
      const name = pair.slice(0, equals).trim();
      if (!COOKIE_NAMES.includes(name)) continue;
      const cookie = pair.slice(equals + 1).trim();
      if (fields[name] !== undefined && fields[name] !== cookie) throw pluginError("Validation");
      fields[name] = cookie;
    }
    const cookies = sessionCookies(fields);
    if (!cookies) throw pluginError("Validation");
    return cookies;
  }

  function browserCookies(snapshot) {
    const fields = {};
    for (const cookie of snapshot.cookies) {
      if (cookie.sourceUrl !== COOKIE_PROBE || !COOKIE_NAMES.includes(cookie.name)) continue;
      const attributes = cookie.attributes;
      // Only cookies suitable for all www API paths become account credentials.
      if (attributes && ((attributes.path && attributes.path !== "/") ||
          (attributes.expiresAt != null && attributes.expiresAt <= Date.now()))) continue;
      if (fields[cookie.name] === undefined) fields[cookie.name] = cookie.value;
    }
    return sessionCookies(fields);
  }

  function headerValues(response, name) {
    return response.headers[name] || [];
  }

  function rotatedCookies(cookies, response) {
    const next = { ...cookies };
    for (const line of headerValues(response, "set-cookie")) {
      const [pair, ...attributes] = line.split(";");
      const equals = pair.indexOf("=");
      if (equals < 1) continue;
      const name = pair.slice(0, equals).trim();
      if (!ROTATING_COOKIES.includes(name)) continue;

      const fields = Object.create(null);
      for (const attribute of attributes) {
        const separator = attribute.indexOf("=");
        if (separator > 0) {
          fields[attribute.slice(0, separator).trim().toLowerCase()] = attribute.slice(separator + 1).trim();
        }
      }
      const domain = (fields.domain || "www.instagram.com").toLowerCase().replace(/^\./, "");
      if ((domain !== "www.instagram.com" && domain !== "instagram.com") || fields.path !== "/") continue;

      const value = pair.slice(equals + 1).trim();
      const maxAge = /^-?\d+$/.test(fields["max-age"]) ? Number(fields["max-age"]) : null;
      const expired = maxAge === null ? Date.parse(fields.expires) <= Date.now() : maxAge <= 0;
      if (!value || expired) {
        delete next[name];
        continue;
      }
      const normalized = normalizeCookie(value);
      if (normalized !== null) next[name] = normalized;
    }
    return next;
  }

  function circuit(accountId) {
    const storage = flare.storage.open("ig_circuit_breaker");
    const key = "account_" + accountId;
    return {
      async get() {
        const snapshot = await storage.read([key]);
        return snapshot.values[key] || {};
      },
      async update(update) {
        // All accounts share the namespace revision. Retry only the local CAS.
        for (let attempt = 0; attempt < 3; attempt++) {
          const snapshot = await storage.read([key]);
          const current = snapshot.values[key] || {};
          const next = update(current);
          if (next === current) return;
          const changes = Object.keys(next).length ? { set: { [key]: next } } : { remove: [key] };
          try {
            await storage.write(changes, { expectedRevision: snapshot.revision });
            return;
          } catch (error) {
            if (error.code !== "storage.conflict" || attempt === 2) throw error;
          }
        }
      },
    };
  }

  function rateError(until) {
    return pluginError("RateLimited", {
      retryAfterSeconds: Math.min(MAX_RETRY_SECONDS, Math.max(0, Math.ceil((until - Date.now()) / 1000))),
    });
  }

  function retryDeadline(response, now) {
    const retryAfter = headerValues(response, "retry-after")[0];
    if (retryAfter) {
      const seconds = /^\d+$/.test(retryAfter) ? Number(retryAfter) : (Date.parse(retryAfter) - now) / 1000;
      if (seconds > 0) return now + Math.min(MAX_RETRY_SECONDS, Math.ceil(seconds)) * 1000;
    }
    return now + COOLDOWN_SECONDS * 1000;
  }

  function decodeResponse(body) {
    try {
      // Keep large, unquoted integer IDs exact on engines with no JSON reviver source.
      // The first alternative consumes complete strings, including escaped content.
      const json = body.trim().replace(/^for \(;;\);\s*/, "");
      if (!json.startsWith("{")) return null;
      return JSON.parse(json.replace(/"(?:\\[\s\S]|[^"\\])*"|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/g, (token) => {
        return /^-?[1-9]\d{15,}$/.test(token) && !Number.isSafeInteger(Number(token)) ? '"' + token + '"' : token;
      }));
    } catch (_) {
      return null;
    }
  }

  function decodeGraphQL(body) {
    const data = decodeResponse(body);
    if (data) return data;
    const frames = body.trim().split(/\r?\n/).filter((line) => line.trim()).map(decodeResponse);
    return frames.length > 1 && frames.every(object) ? frames : null;
  }

  function remoteMessages(data) {
    if (!object(data)) return [];
    const messages = [data.message, data.error, data.error_type, data.status, data.errorSummary];
    if (Array.isArray(data.errors)) {
      for (const error of data.errors) {
        if (object(error)) messages.push(error.message, error.error_type, error.summary);
      }
    }
    return messages.filter((message) => typeof message === "string").map((message) => message.trim().toLowerCase());
  }

  function requestUrl(endpoint, id, query) {
    const path = id === null ? endpoint : endpoint.replace("{id}", encodeURIComponent(id));
    const parameters = Object.entries(query).filter(([, value]) => value !== undefined);
    const suffix = parameters.map(([key, value]) => encodeURIComponent(key) + "=" + encodeURIComponent(value)).join("&");
    return ORIGIN + path + (suffix ? "?" + suffix : "");
  }

  async function igRequest(context, cookies, endpoint, id, query, options = {}) {
    requireInstagram(context);
    const { loginFlowId, decode = decodeResponse, form } = options;
    const breaker = circuit(cookies.ds_user_id);
    const state = await breaker.get();
    if (state.until > Date.now()) throw rateError(state.until);
    if (state.auth && (!loginFlowId || state.auth.flowId === loginFlowId)) {
      throw pluginError("AuthenticationRequired");
    }

    const headers = {
      "Accept": options.accept || "application/json",
      "Accept-Language": /^zh(?:-|$)/i.test(context.locale) ? "zh-CN,zh;q=0.9,en;q=0.8" : "en-US,en;q=0.9",
      "Referer": options.referer || COOKIE_PROBE,
      "User-Agent": USER_AGENT,
      "X-IG-App-ID": WEB_APP_ID,
      "X-Requested-With": "XMLHttpRequest",
    };
    if (cookies.csrftoken) headers["X-CSRFToken"] = cookies.csrftoken;
    if (form) {
      headers.Origin = ORIGIN;
      headers["X-FB-LSD"] = form.lsd;
      headers["X-FB-Friendly-Name"] = FOLLOWING_QUERY.name;
      headers["X-Root-Field-Name"] = FOLLOWING_QUERY.connection;
    }
    let response;
    try {
      response = await flare.http.request({
        method: form ? "POST" : "GET",
        url: requestUrl(endpoint, id, query),
        headers,
        cookies: Object.entries(cookies).map(([name, value]) => ({ name, value })),
        ...(form ? { body: { type: "form", values: form } } : {}),
        redirect: "manual",
      });
    } catch (_) {
      throw pluginError("Network");
    }

    const data = response.status >= 200 && response.status < 300 ? decode(response.body) : decodeResponse(response.body);
    const errorData = data ?? (decode === decodeResponse ? null : decodeResponse(response.body));
    const messages = Array.isArray(errorData) ? errorData.flatMap(remoteMessages) : remoteMessages(errorData);
    let authenticationCode = messages.find((message) =>
      /^(?:(?:login|challenge|checkpoint)_required|feedback_required)$/.test(message));
    if (!authenticationCode && response.status >= 300 && response.status < 400) {
      const redirect = /^https:\/\/www\.instagram\.com\/(challenge|checkpoint)(?:\/|\?|$)/.exec(response.redirectUrl || "");
      if (redirect) authenticationCode = redirect[1] + "_required";
    }
    if (response.status === 429 || messages.some((message) =>
      /^(rate_limit(?:ed|_error)?|throttled|please wait a few minutes before you try again\.?|too many requests)$/.test(message))) {
      const until = retryDeadline(response, Date.now());
      await breaker.update((current) => ({ ...current, until: Math.max(current.until || 0, until) }));
      throw rateError(until);
    }
    if (response.status === 401 || response.status === 403 || authenticationCode) {
      const auth = { at: Date.now(), flowId: loginFlowId || null };
      await breaker.update((current) => current.auth && !loginFlowId ? current : { ...current, auth });
      throw pluginError("AuthenticationRequired", authenticationCode ? { remoteCode: authenticationCode } : undefined);
    }
    if (response.status === 404 || response.status === 410) throw pluginError("NotFound");
    if (response.status >= 300 && response.status < 400) throw pluginError("InvalidResponse");
    if (response.status === 400 && options.allowBadRequest) {
      return { data: null, cookies, breaker, previousAuth: state.auth };
    }
    if (response.status < 200 || response.status >= 300 || (object(data) && data.status === "fail")) {
      throw pluginError("Remote");
    }
    if (data === null) throw pluginError("InvalidResponse");
    return { data, cookies: rotatedCookies(cookies, response), breaker, previousAuth: state.auth };
  }

  async function accountSession(context) {
    requireInstagram(context);
    const snapshot = await flare.credential.read({ withRevision: true });
    const value = snapshot.value;
    const cookies = object(value) && object(value.cookies) ? sessionCookies(value.cookies) : null;
    if (!cookies || (context.accountId && context.accountId !== cookies.ds_user_id)) {
      throw pluginError("AuthenticationRequired");
    }
    return { cookies, revision: snapshot.revision };
  }

  async function accountRequest(context, endpoint, id, query, options, session) {
    const account = session || await accountSession(context);
    const response = await igRequest(context, account.cookies, endpoint, id, query, options);
    if (ROTATING_COOKIES.some((name) => response.cookies[name] !== account.cookies[name])) {
      // A stale response must never overwrite a concurrent cookie rotation or relogin.
      await flare.credential.replace({ cookies: response.cookies }, { expectedRevision: account.revision });
    }
    return response.data;
  }

  async function completeLogin(cookies, request, context) {
    const options = { loginFlowId: request.flowId };
    let verifiedCookies = cookies;
    if (!cookieOwner(cookies.sessionid)) {
      // The profile endpoint also works for arbitrary users. Check the logged-in
      // viewer first when the session Cookie does not encode an account ID.
      const identity = await igRequest(context, cookies, "/", null, {}, {
        ...options,
        accept: "text/html,application/xhtml+xml",
        decode: (body) => {
          const modules = parseBootstrap(body);
          return modules.viewerId ? modules : null;
        },
      });
      if (identity.data.viewerId !== cookies.ds_user_id) await recordLoginMismatch(identity.breaker, request.flowId);
      verifiedCookies = identity.cookies;
    }
    const response = await igRequest(context, verifiedCookies, ENDPOINTS.profileById, cookies.ds_user_id, {}, options);
    const profile = mapProfile(response.data.user, context);
    if (profile.key.id !== cookies.ds_user_id) {
      await recordLoginMismatch(response.breaker, request.flowId);
    }
    followingSession = undefined;
    await response.breaker.update((current) => {
      const previous = response.previousAuth;
      if (current.auth && previous && current.auth.at === previous.at && current.auth.flowId === previous.flowId) {
        const { auth, ...next } = current;
        return next.until > Date.now() ? next : {};
      }
      return current;
    });
    return {
      type: "success",
      value: {
        accountId: profile.key.id,
        origin: context.origin,
        credential: { cookies: response.cookies },
        profile,
        capabilities: capabilities(),
      },
    };
  }

  async function recordLoginMismatch(breaker, flowId) {
    await breaker.update((current) => ({ ...current, auth: { at: Date.now(), flowId } }));
    throw pluginError("AuthenticationRequired");
  }

  function safeMediaUrl(value) {
    if (typeof value !== "string" || value.length > 8192 || /[\s\\\u0000-\u001f]/.test(value)) return null;
    const match = /^https:\/\/([A-Za-z0-9.-]+)(?:\/|$)/i.exec(value);
    if (!match) return null;
    const host = match[1].toLowerCase();
    return host === "www.instagram.com" || /(?:^|\.)(?:cdninstagram\.com|fbcdn\.net)$/.test(host) ? value : null;
  }

  function count(value) {
    return Number.isSafeInteger(value) && value >= 0 && value <= 2147483647 ? value : undefined;
  }

  function dimension(value) {
    return Number.isSafeInteger(value) && value > 0 && value <= 100000 ? value : undefined;
  }

  function plain(value) {
    return { format: "plain", value: typeof value === "string" ? value.slice(0, 1000000) : "" };
  }

  function mapProfile(user, context) {
    if (!object(user)) throw pluginError("InvalidResponse");
    const id = decimalId(user.pk) || decimalId(user.id);
    const handle = username(user.username);
    if (!id || !handle) throw pluginError("InvalidResponse");
    return {
      key: entityKey(id, context),
      handle,
      displayName: typeof user.full_name === "string" && user.full_name.trim() ? user.full_name.slice(0, 1024) : handle,
      avatarUrl: safeMediaUrl(user.profile_pic_url_hd) || safeMediaUrl(user.hd_profile_pic_url_info?.url) ||
        safeMediaUrl(user.profile_pic_url) || undefined,
      url: ORIGIN + "/" + handle + "/",
      description: typeof user.biography === "string" ? plain(user.biography) : undefined,
      verified: typeof user.is_verified === "boolean" ? user.is_verified : undefined,
      locked: typeof user.is_private === "boolean" ? user.is_private : undefined,
      followersCount: count(user.follower_count ?? user.edge_followed_by?.count),
      followingCount: count(user.following_count ?? user.edge_follow?.count),
      postsCount: count(user.media_count ?? user.edge_owner_to_timeline_media?.count),
    };
  }

  function rankedMedia(versions) {
    if (!Array.isArray(versions)) return [];
    return versions.filter((candidate) => object(candidate) && safeMediaUrl(candidate.url)).sort((a, b) =>
      (dimension(b.width) || 0) * (dimension(b.height) || 0) - (dimension(a.width) || 0) * (dimension(a.height) || 0));
  }

  function mediaId(item) {
    return decimalId(item.pk) || decimalId(item.id) ||
      (typeof item.id === "string" && /^\d+_\d+$/.test(item.id) ? decimalId(item.id.split("_", 1)[0]) : null);
  }

  function mapMedia(item, id) {
    const images = rankedMedia(item.image_versions2?.candidates);
    const videos = item.media_type === 2 ? rankedMedia(item.video_versions) : [];
    const video = videos[0];
    const main = video || images[0];
    if (!main || (item.media_type !== 1 && item.media_type !== 2)) return null;
    const preview = video ? images[0] : images.find((image) => image.url !== main.url);
    const duration = video && typeof item.video_duration === "number" ? Math.round(item.video_duration * 1000) : undefined;
    return {
      id,
      type: video ? "video" : "image",
      url: main.url,
      previewUrl: preview?.url,
      width: dimension(main.width) || dimension(item.original_width),
      height: dimension(main.height) || dimension(item.original_height),
      description: typeof item.accessibility_caption === "string" ? item.accessibility_caption.slice(0, 100000) : undefined,
      durationMillis: Number.isSafeInteger(duration) && duration >= 0 && duration <= 604800000 ? duration : undefined,
    };
  }

  function isoTime(seconds) {
    if (typeof seconds === "string" && /^\d{1,12}$/.test(seconds)) seconds = Number(seconds);
    if (typeof seconds !== "number" || !(seconds > 0 && seconds < 253402300800)) throw pluginError("InvalidResponse");
    return new Date(seconds * 1000).toISOString();
  }

  function shortcode(id) {
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let number = BigInt(id);
    let result = "";
    while (number > 0n) {
      result = alphabet[Number(number % 64n)] + result;
      number /= 64n;
    }
    return result;
  }

  function postUrl(item, id) {
    const code = typeof item.code === "string" && /^[A-Za-z0-9_-]{1,30}$/.test(item.code) ? item.code : shortcode(id);
    return ORIGIN + "/p/" + code + "/";
  }

  function mapPost(item, context) {
    const id = object(item) ? mediaId(item) : null;
    if (!id) throw pluginError("InvalidResponse");
    const author = mapProfile(item.user, context);
    const media = item.media_type === 8 && Array.isArray(item.carousel_media)
      ? item.carousel_media.slice(0, 100).map((child, index) =>
        object(child) ? mapMedia(child, mediaId(child) || id + ":" + index) : null).filter(Boolean)
      : [mapMedia(item, id)].filter(Boolean);
    return {
      key: entityKey(id, context),
      author,
      createdAt: isoTime(item.taken_at),
      content: plain(item.caption?.text),
      url: postUrl(item, id),
      media,
      favouritesCount: count(item.like_count),
      repliesCount: count(item.comment_count),
      visibility: author.locked === undefined ? undefined : author.locked ? "followers" : "public",
      actions: [],
    };
  }

  function mapComment(comment, parentId, parentUrl, context) {
    const id = object(comment) ? decimalId(comment.pk) || decimalId(comment.id) : null;
    if (!id || typeof comment.text !== "string") throw pluginError("InvalidResponse");
    return {
      key: entityKey("comment:" + id, context),
      author: mapProfile(comment.user, context),
      createdAt: isoTime(comment.created_at || comment.created_at_utc),
      content: plain(comment.text),
      url: parentUrl + "?comment_id=" + id,
      detailType: "link",
      replyTo: entityKey(parentId, context),
      favouritesCount: count(comment.comment_like_count),
      actions: [],
    };
  }

  function parseBootstrap(html) {
    const script = /<script\b([^>]*)>/gi;
    const lowerHtml = html.toLowerCase();
    const modules = {};
    let tag;
    while ((tag = script.exec(html)) !== null) {
      const end = lowerHtml.indexOf("</script", script.lastIndex);
      if (end < 0) break;
      const text = html.slice(script.lastIndex, end);
      script.lastIndex = end + "</script".length;
      if (!/(?:^|\s)type\s*=\s*(?:"application\/json"|'application\/json'|application\/json)(?=\s|$)/i.test(tag[1])) continue;
      if (!/"(?:DTSGInitialData|LSD|CurrentUserInitialData|PolarisViewer|PolarisSiteData)"/.test(text)) continue;
      const json = decodeResponse(text);
      if (!json) continue;
      const pending = [json];
      while (pending.length) {
        const entry = pending.pop();
        if (Array.isArray(entry) && object(entry[2])) {
          if (entry[0] === "DTSGInitialData") modules.fb_dtsg = normalizeCookie(entry[2].token);
          if (entry[0] === "LSD") modules.lsd = normalizeCookie(entry[2].token);
          if (entry[0] === "CurrentUserInitialData") modules.av = decimalId(entry[2].IG_USER_EIMU);
          if (entry[0] === "PolarisViewer") modules.viewerId = decimalId(entry[2].data?.id);
          if (entry[0] === "PolarisSiteData") modules.deviceId = normalizeCookie(entry[2].device_id);
        }
        if (Array.isArray(entry) || object(entry)) {
          for (const value of Object.values(entry)) pending.push(value);
        }
      }
      if (modules.fb_dtsg && modules.lsd && modules.av && modules.viewerId && modules.deviceId) break;
    }
    return modules;
  }

  async function followingTokens(context, session) {
    const sessionId = session.cookies.sessionid;
    if (followingSession?.sessionId === sessionId && followingSession.expiresAt > Date.now()) {
      return followingSession.tokens;
    }
    const tokens = await accountRequest(context, FOLLOWING_QUERY.page, null, {}, {
      accept: "text/html,application/xhtml+xml",
      decode: (body) => {
        const modules = parseBootstrap(body);
        return modules.fb_dtsg && modules.lsd ? modules : null;
      },
    }, session);
    followingSession = { sessionId, tokens, expiresAt: Date.now() + 15 * 60 * 1000 };
    return tokens;
  }

  function followingForm(page, state, cookies, tokens) {
    let checksum = 0;
    for (const character of tokens.fb_dtsg) checksum += character.charCodeAt(0);
    return {
      "__d": "www",
      ...(tokens.av ? { av: tokens.av } : {}),
      fb_dtsg: tokens.fb_dtsg,
      jazoest: "2" + checksum,
      lsd: tokens.lsd,
      fb_api_caller_class: "RelayModern",
      fb_api_req_friendly_name: FOLLOWING_QUERY.name,
      doc_id: FOLLOWING_QUERY.documentId,
      server_timestamps: "true",
      variables: JSON.stringify({
        after: state.next || null,
        before: null,
        first: Math.min(page.limit, 20),
        last: null,
        variant: "following",
        data: {
          device_id: tokens.deviceId || cookies.ig_did,
          is_async_ads_double_request: "0",
          is_async_ads_in_headload_enabled: "0",
          is_async_ads_rti: "0",
          rti_delivery_backend: "0",
          pagination_source: "following",
        },
        __relay_internal__pv__PolarisIsLoggedInrelayprovider: true,
        __relay_internal__pv__PolarisShortDramaEnabledrelayprovider: true,
        __relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider: true,
        __relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider: false,
        __relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider: false,
        __relay_internal__pv__PolarisAdDebugToolEnabledrelayprovider: false,
        __relay_internal__pv__PolarisSuggestedUserFeedUnitCardsEnabledrelayprovider: false,
      }),
    };
  }

  function followingConnection(result) {
    const frames = Array.isArray(result) ? result : [result];
    const key = FOLLOWING_QUERY.connection;
    let connection;
    for (const frame of frames) {
      const data = frame.data?.[key];
      if (object(data)) {
        connection = { ...data, edges: Array.isArray(data.edges) ? data.edges.slice() : null };
      }
      if (!connection || !Array.isArray(frame.path) || frame.path[0] !== key) continue;
      const [, field, index] = frame.path;
      if (field === "page_info" && object(frame.data)) connection.page_info = frame.data;
      if (field === "edges" && connection.edges && Number.isSafeInteger(index) && index >= 0 && index < 200) {
        if (Array.isArray(frame.items) && index + frame.items.length <= 200) {
          frame.items.forEach((edge, offset) => { connection.edges[index + offset] = edge; });
        } else if (object(frame.data)) {
          connection.edges[index] = frame.data;
        }
      }
    }
    if (!Array.isArray(connection?.edges) || !object(connection.page_info) || connection.pagination_source === "home") {
      throw pluginError("InvalidResponse");
    }
    return connection;
  }

  function remoteCursor(value) {
    if (Number.isSafeInteger(value) && value > 0) value = String(value);
    return typeof value === "string" && value.length > 0 && value.length < MAX_CURSOR_LENGTH && !/[\r\n\0]/.test(value)
      ? value
      : undefined;
  }

  function pagination(page, kind) {
    if (page.direction === "refresh") return { kind, recent: [], seen: [] };
    if (page.direction !== "older" || typeof page.cursor !== "string" || page.cursor.length > MAX_CURSOR_LENGTH) {
      throw pluginError("Unsupported");
    }
    const state = decodeResponse(page.cursor);
    if (state?.kind !== kind || !remoteCursor(state.next) || !Array.isArray(state.recent) || !Array.isArray(state.seen)) {
      throw pluginError("Unsupported");
    }
    return state;
  }

  function pagedItems(items, state, next, additional = {}) {
    if (items.length > 200) throw pluginError("InvalidResponse");
    const seen = new Set(state.seen);
    const unique = items.filter((item) => {
      const id = item.post.key.id;
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    });
    if (!unique.length || !next || next === state.next || state.recent.includes(next)) {
      return { items: unique, endReached: true };
    }
    const cursor = {
      kind: state.kind,
      next,
      recent: state.next ? [state.next] : [],
      seen: [...seen].slice(-80),
      ...additional,
    };
    let olderCursor = JSON.stringify(cursor);
    if (olderCursor.length > MAX_CURSOR_LENGTH) {
      cursor.recent = [];
      cursor.seen = [];
      olderCursor = JSON.stringify(cursor);
    }
    return olderCursor.length <= MAX_CURSOR_LENGTH
      ? { items: unique, olderCursor, endReached: false }
      : { items: unique, endReached: true };
  }

  function adOrSuggestion(item) {
    return Boolean(item.ad || item.ad_id || item.ad_token || item.injected || item.is_ad ||
      item.suggested_users || item.clips_netego || item.suggested_user_feed_unit || item.recommended_users);
  }

  function timelinePosts(entries, context) {
    if (!Array.isArray(entries) || entries.length > 200) throw pluginError("InvalidResponse");
    const items = [];
    for (const item of entries) {
      if (!object(item) || adOrSuggestion(item) || !mediaId(item) || ![1, 2, 8].includes(item.media_type)) continue;
      items.push({ type: "post", post: mapPost(item, context) });
    }
    return items;
  }

  function requestedId(key, context) {
    requireInstagram(context);
    const id = decimalId(key.id);
    if (!id || key.host !== entityKey(id, context).host) throw pluginError("Unsupported");
    return id;
  }

  async function loadFollowing(request, context) {
    if (request.timelineId !== "home") throw pluginError("Unsupported");
    const state = pagination(request.page, "home");
    const session = await accountSession(context);
    const tokens = await followingTokens(context, session);
    const active = await accountSession(context);
    const currentTokens = active.cookies.sessionid === session.cookies.sessionid ? tokens : await followingTokens(context, active);
    let connection;
    try {
      const result = await accountRequest(context, FOLLOWING_QUERY.path, null, {}, {
        decode: decodeGraphQL,
        referer: ORIGIN + FOLLOWING_QUERY.page,
        form: followingForm(request.page, state, active.cookies, currentTokens),
      }, active);
      connection = followingConnection(result);
    } catch (error) {
      followingSession = undefined;
      throw error;
    }
    const entries = connection.edges.filter((edge) => object(edge?.node) && !adOrSuggestion(edge.node))
      .map((edge) => edge.node.media);
    const next = connection.page_info.has_next_page === true ? remoteCursor(connection.page_info.end_cursor) : undefined;
    return pagedItems(timelinePosts(entries, context), state, next);
  }

  async function profileById(id, context) {
    const data = await accountRequest(context, ENDPOINTS.profileById, id, {});
    const profile = mapProfile(data.user, context);
    if (profile.key.id !== id) throw pluginError("InvalidResponse");
    return profile;
  }

  async function profileByHandle(request, context) {
    requireInstagram(context);
    if (request.host !== "www.instagram.com" && request.host !== "instagram.com") throw pluginError("Unsupported");
    const handle = username(request.handle.trim().replace(/^@/, "").replace(/@(?:www\.)?instagram\.com$/i, ""));
    if (!handle) throw pluginError("NotFound");
    let profile;
    const data = await accountRequest(context, ENDPOINTS.profileByHandle, null, { username: handle }, { allowBadRequest: true });
    if (data === null) {
      // Instagram rejects the Web profile lookup for some professional profiles with HTTP 400.
      const feed = await accountRequest(context, ENDPOINTS.profileByHandleFeed, handle, { count: 1 });
      const id = object(feed.user) ? decimalId(feed.user.pk) || decimalId(feed.user.id) : null;
      if (!id) throw pluginError("InvalidResponse");
      profile = await profileById(id, context);
    } else {
      if (data.data?.user === null) throw pluginError("NotFound");
      profile = mapProfile(data.data?.user, context);
    }
    if (profile.handle.toLowerCase() !== handle.toLowerCase()) throw pluginError("InvalidResponse");
    return profile;
  }

  async function loadProfilePosts(request, context) {
    const id = requestedId(request.profile, context);
    if (request.tabId && request.tabId !== "posts" && request.tabId !== "gallery") throw pluginError("Unsupported");
    const state = pagination(request.page, "profile:" + id);
    const data = await accountRequest(context, ENDPOINTS.profilePosts, id, { count: request.page.limit, max_id: state.next });
    const next = data.more_available === false ? undefined : remoteCursor(data.next_max_id);
    return pagedItems(timelinePosts(data.items, context), state, next);
  }

  async function loadPost(id, context) {
    const data = await accountRequest(context, ENDPOINTS.post, id, {});
    if (!Array.isArray(data.items)) throw pluginError("InvalidResponse");
    const item = data.items.find((candidate) => object(candidate) && mediaId(candidate) === id);
    if (!item) throw pluginError(data.items.length ? "InvalidResponse" : "NotFound");
    return mapPost(item, context);
  }

  async function loadComments(request, context) {
    const id = requestedId(request.key, context);
    const state = pagination(request.page, "comments:" + id);
    const root = request.page.direction === "refresh" ? await loadPost(id, context) : null;
    const rawCode = root ? root.url.slice(ORIGIN.length + 3, -1) : state.code;
    const code = typeof rawCode === "string" && /^[A-Za-z0-9_-]{1,30}$/.test(rawCode) ? rawCode : shortcode(id);
    const data = await accountRequest(context, ENDPOINTS.comments, id, {
      can_support_threading: "true",
      permalink_enabled: "false",
      min_id: state.param === "min_id" ? state.next : undefined,
      max_id: state.param === "max_id" ? state.next : undefined,
    });
    const comments = data.comments_disabled === true ? [] : data.comments;
    if (!Array.isArray(comments) || comments.length > 199) throw pluginError("InvalidResponse");
    const items = comments.map((comment) => ({ type: "post", post: mapComment(comment, id, ORIGIN + "/p/" + code + "/", context) }));
    const min = data.has_more_headload_comments === false ? undefined : remoteCursor(data.next_min_id);
    const max = data.has_more_comments === false ? undefined : remoteCursor(data.next_max_id);
    const page = pagedItems(items, state, min || max, { param: min ? "min_id" : "max_id", code });
    if (root) page.items.unshift({ type: "post", post: root });
    return page;
  }

  defineFlareSocialPlugin({
    detector: { detect },
    login: {
      browser: {
        begin(_request, context) {
          requireInstagram(context);
          return { type: "webCookie", startUrl: LOGIN_URL };
        },
        check(request, context) {
          requireInstagram(context);
          const cookies = browserCookies(request.cookies);
          return cookies ? completeLogin(cookies, request, context) : { type: "pending" };
        },
      },
      importCookies: {
        begin(request, context) {
          requireInstagram(context);
          return completeLogin(importedCookies(request.values.cookie), request, context);
        },
      },
    },
    capabilities: {
      accountCapabilities: {
        negotiate(_request, context) {
          requireInstagram(context);
          return { capabilities: capabilities() };
        },
      },
      timeline: { page: loadFollowing },
      profile: {
        byId(request, context) {
          return profileById(requestedId(request.key, context), context);
        },
        byHandle: profileByHandle,
        timeline: loadProfilePosts,
      },
      post: {
        async detail(request, context) {
          return { post: await loadPost(requestedId(request.key, context), context) };
        },
        context: loadComments,
      },
    },
  });
})();
