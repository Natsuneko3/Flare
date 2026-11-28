# Capability and UI contract

Each ID below has the prefix `flare.datasource.`. Append `/v1` to entries that
omit a version. Use the full ID in the manifest and negotiated login result.
JavaScript methods live under
`defineFlareSocialPlugin({ capabilities: { <service>: ... } })`.

| ID after prefix | Service | Required operations | Additional operations | Host surface / dependency |
| --- | --- | --- | --- | --- |
| `timeline-page` | `timelinePage` | `load` | None | API v2 temporary native lists; no home timeline or tabCatalog required |
| `account-http` | `accountHttp` | `authenticate` | None | API v2 account-only authentication for platform extensions; explicit authOrigins; no displayable entry |
| `session-challenge` | `sessionChallenge` | `complete` | None | API v2 account-only interactive WebView challenge recovery; requires `challengeProbes`; no displayable entry; non-auto-granted on upgrade (pair with `account-capabilities/v1`) |
| `account-capabilities` | `accountCapabilities` | `negotiate` | None | Optional read-only account renegotiation after upgrade; runtime APIs 1 and 2; no displayable entry |
| `timeline` | `timeline` | `page` | None | Static timelines, including at least one default |
| `search` | `search` | At least one search operation | `posts`, `profiles`, `discoverPosts`, `discoverProfiles`, `discoverHashtags` | Each supported operation enables its own section |
| `profile` | `profile` | `byId`, `byHandle` | `timeline`, `following`, `followers` | Profile lookup; tabs additionally require `timeline` and profileTabs |
| `post` | `post` | `detail` (or `mutate` alone in API v2) | `context`, `delete`, `mutate`, `contextTabs` and `contextPage` (API v2 pair) | Internal post detail (`detail` required), thread context, tabbed detail, and supported semantic actions (`mutate` alone is not a displayable login capability) |
| `relation` | `relation` | `state`, `mutate` | None | Follow/block/mute controls; nonempty static relationActions |
| `compose/v1` | `compose` | `publish` | None | Legacy new posts and replies via `replyTo`. Requires `composeDefaults` and an account. |
| `compose/v2` | `compose` | `publish` | None | Typed reply and quote references. Requires `composeDefaults.supportedTypes` and an account. |
| `compose/v3` | `composeV3` | `publish` | None | Composer with friends and selfOnly visibility. Requires composeDefaultsV3 and account negotiation. |
| `notification` | `notification` | At least one of `page`, `badge` | Both may be declared | `page` supplies the list and requires notificationFilters; badge alone is not a displayable login capability |
| `list` | `list` | `page`, `detail`, `create`, `update`, `delete`, `timeline`, `members`, `memberships`, `addMember`, `removeMember` | None | List browsing and management |
| `direct-message` | `directMessage` | `rooms`, `room`, `messages`, `send`, `delete`, `leave`, `create`, `badge`, `canSend` | None | DM rooms, conversation, and related account controls |
| `article` | `article` | `detail`, `comments` | None | Article detail when a post selects article navigation |
| `gallery` | `gallery` | `detail`, `comments`, `recommendations` | `ugoiraMetadata` | Gallery detail when a post selects gallery navigation |
| `manga` | `manga` | `detail`, `pages` | `pageImage`, `comments`, `recommendations` | API v2 manga detail and reader when a post or target selects manga or chapter navigation |
| `tab-catalog` | `tabCatalog` | `page` | None | Dynamic timeline catalog; also needs timeline.page and a static/default timeline |

These sets come from the Host's `PluginAbiV1` contract, not from TypeScript
`Partial<Service>` declarations. Known capability operations must be implemented
and declared together. A website without a required operation may need a smaller
feature set; do not advertise a successful mutation that does nothing. A genuine
empty comments collection may return an empty ended page; an unsupported remote
operation should be explained and omitted where the Host allows omission.

## Declaration, registration, negotiation

For the same timeline, these three shapes are intentionally different:

```json
{ "flare.datasource.timeline/v1": { "operations": { "page": { "directions": ["refresh", "older"] } } } }
```

```js
// Registration fragment: include inside the single defineFlareSocialPlugin call.
const timelineService = {
  async page(request, context) {
    return loadTimeline(request.timelineId, request.page, context);
  },
};
```

```json
{ "flare.datasource.timeline/v1": ["page"] }
```

The first is `platform.capabilities`, the second is `capabilities.timeline`
(with a website-specific `loadTimeline` implementation), and the third is the
login success's `capabilities`. On login, negotiate only declared, complete sets
that this account/instance supports. At least one displayable capability is
required: compose/relation/account-http alone and a notification badge alone do not qualify.
Guest access uses the enabled guest declaration and manifest capabilities instead
of account login negotiation. See [upgrade negotiation](publishing.md#updates-and-compatibility)
for how an existing account adopts newly declared operations after an update.

## Entry points and actions

The Host builds UI from capabilities and static configuration. Implementing an
undeclared function does not add a UI entry. A static timeline ID is passed as
`request.timelineId`; profile tabs pass `request.tabId`. Names and parameters are
not network URLs unless your implementation explicitly interprets them that way.

Post actions additionally depend on their `ActionDescriptorV1`, enabled state,
account, and mutation capability. Set accurate `active`/`count` values even when
an action is disabled. The Host supplies layout and controls; plugins cannot
install arbitrary UI or arbitrary action kinds.

| `PostV1.detailType` | Click behavior | Required target |
| --- | --- | --- |
| Omitted or `post` | Internal post detail | post.detail |
| `article` | Internal article detail | article.detail + comments |
| `gallery` | Internal gallery detail | gallery.detail + comments + recommendations |
| `manga` | Internal manga detail | manga.detail + pages (API v2) |
| `link` | Browser according to user settings | Absolute HTTPS `url`, no internal-detail capability needed |

Unknown string detail types keep the preview and disable post, cover, and grid
clicks. Omitting detailType still defaults to post. Unknown navigation targets
also disable the click, without applying an absent-target fallback.

Missing internal target capability makes that click unavailable; it does not
fall back to another detail type. `link` bypasses internal Deep Link matching.
Grid covers use the same article/gallery/manga/link navigation; author/actions and
reposted targets retain their own behavior.

The login profile remains the current account's saved identity if profile lookup
is absent. It does not enable arbitrary profile pages, profile timelines, or
lookup methods. Use this for a minimal read-only feed such as the template.

See [recipes](recipes.md) for every service's inputs, result shape, and adaptation
points, and [the type declarations](../flare-plugin.d.ts) for full signatures.

## API v2 post detail tabs

Declare `contextTabs` and `contextPage` together under `flare.datasource.post/v1`.
Implement both methods in `capabilities.post` and include both in account login
or upgrade negotiation for supported instances. Guests use the manifest
declaration. `detail` remains required.

`contextTabs` supplies the tabs and optional filters for a particular post.
`contextPage` loads rows for the selected tab and filter. The host uses the
legacy `context` operation for detail when the pair is unavailable, and prefers
it for sharing replies when both are available. [Post detail tab recipe](recipes.md#post-detail-tabs)

## API v2 account HTTP providers

`flare.datasource.account-http/v1` lets another plugin extend this platform's
existing accounts. Declare `authOrigins`, negotiate `authenticate` at login, and
implement `capabilities.accountHttp.authenticate(request, context)`. The Host gives
the provider its own account context and the exact request method, URL, headers
and encoded body; returned authentication headers stay inside the Host. The
consumer needs a separate user grant and cannot access provider credentials or
storage. See [platform extensions](platform-extensions.md#supplying-authentication-from-a-social-plugin)
for the manifest, signatures, origin restrictions and lifecycle rules.

## API v2 temporary timeline pages

Declare `flare.datasource.timeline-page/v1` with operation `load` and negotiate
it at login, then implement `capabilities.timelinePage.load({ pageId, page }, context)`.
This is a plugin API v2 feature; the capability itself starts at version v1.
It does not require `timeline`, `tabCatalog`, a static/default timeline or a page catalog.
The plugin must still have a normal displayable capability as its account entry point.

Posts and quote posts may set:

```js
target: {
  type: "timelinePage", pageId: "replies", title: { value: "Replies" },
  parameters: { parentId: "comment:123", sort: "oldest" }
}
```

`PostV1.target` accepts all existing navigation targets as well. An omitted/null
target retains `detailType` navigation. Explicit unsupported targets are inert,
never silently replaced with the post's default detail. Existing card, quote,
media, avatar, link and action hit handling remains unchanged.

The Host binds the originating plugin, package version and account, copies target
parameters into `page.parameters` on every call, and supplies the normal paging
direction, limit and cursor. Return `PageV1<TimelineItemV1>`; an empty page may
advance its cursor. Unknown page IDs and remote failures must use the existing
plugin error protocol rather than a successful empty list.

The native page is a single column with a title, back navigation, refresh and
pagination. It preserves supplied order without collapsing reply chains. Parent
context, when useful, can be an ordinary returned row. There are no home tabs,
page-level compose controls, sort controls or persistent page configuration.

For one account, keep the same key's target type, destination and parameters
consistent across timelines, quotes, `post.detail`, `updatedPost` and publish
results; these are full entities sharing the existing cache. Do not change an
entity key just to give one occurrence a different target. Navigation parameters
do not become Compose context: comment keys must also work with `post.detail`
and the existing reply reference. Like/reply/delete controls remain subject to
their ordinary capabilities. Successful publish/delete invalidates this account's
temporary lists; hidden lists refresh when shown again.

## API v2 manga reader and worker concurrency

`flare.datasource.manga/v1` requires `detail` and `pages` plus `permissions.mediaNetwork: true`.
Optional `pages` request fields (`offset`, `limit`, `cursor`) and response fields
(`offset`, `totalCount`, `nextCursor`, `endReached` defaulting to `true`) support
chunked and cursor-based chapter loading. Optional `pageImage` receives retry
context (`attempt`, `reason`, `failedUrl`) when resolving or reloading deferred pages.

> **Concurrency warning:** Up to 3 `manga.pageImage` invocations run in parallel
> across separate isolated worker VMs (`workerLane`). Cross-page or cross-chunk
> state (such as `ptoken` lookup tables populated during `manga.pages`) MUST be
> stored in `flare.storage` (`apiVersion >= 2`), never in JavaScript module globals.

See [manga pagination and parallel resolution](wire-data.md#manga-pagination-and-parallel-image-resolution)
for exact chunk alignment, overlap consistency, and `totalCount` validation rules.

## API v2 session challenge recovery

Declare `flare.datasource.session-challenge/v1` with operation `complete` and
`challengeProbes` (`1..16` probes on `$accountOrigin` or `permissions.authOrigins`).
When an authenticated account call throws `flare.error({ code: "ChallengeRequired",
message, challenge: { url, seedCookies? } })`, Flare presents an interactive WebView
and invokes `capabilities.sessionChallenge.complete` until it returns `{ type: "completed" }`.
`challengeProbes` are audited as `Cookie` permissions on install and upgrade, and
existing accounts do not receive this capability automatically on upgrade — strongly
pair it with `flare.datasource.account-capabilities/v1` so saved accounts can
adopt it via `negotiate` without re-login. See
[session challenge recovery](authentication.md#session-challenge-recovery-api-v2).
