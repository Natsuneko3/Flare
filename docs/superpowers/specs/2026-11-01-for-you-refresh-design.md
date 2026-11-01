# X For You manual recommendation batches

The user selected the second proposed approach: pull down at the top of the standalone X For You page to replace its recommendation batch, prioritizing unseen posts. Other timelines retain their existing behavior.

The simpler alternative only reloads the first page and can repeatedly show the same posts. The selected approach adds bounded, per-account/page session history of actually viewed posts, sends seen IDs to X, filters repeats locally, and makes up to three requests to find a useful batch. X still controls available recommendations; no invented requestContext value or guaranteed novelty.

Only manual pull triggers this policy. Automatic refresh, switching tabs, returning from media, Following, mixed feeds, lists and search retain existing behavior. The standalone For You page gets a separate cache namespace. Shared post entities are not deleted. Loading keeps the current list; a successful batch atomically replaces page references and cursor. Empty/repeated/filtered results or failure retain the current list and show a short message. A successful pull returns to the top even when reading-position memory is enabled. Visible-post history is bounded to 200 per session and only records the active foreground page.

Validation covers cancellation, repeated cursor, filtering, account/page isolation, concurrent append/refresh, reading-position memory and ordinary timeline regressions. Build, device verification and the established signed GitHub Release workflow follow implementation.
