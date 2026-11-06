<script lang="ts">
	import { tick, untrack } from 'svelte';
	import { TimelineWindow, timelineItemKey } from './timelineWindow';
	import { useEnvironmentSettings } from '$lib/environment/environmentSettings.svelte';
	import { m } from '$lib/paraglide/messages.js';
	import type { TimelineDisplayMode } from '$lib/environment/environmentSettings.svelte';
	import type { MicroBlogKey, PagingState, UiTimelineV2 } from '@flare/web-presenters/timeline.svelte';
	import PostLoadingPlaceholder from './post/PostLoadingPlaceholder.svelte';
	import { defaultTimelineAppearance } from './post/postUtils';
	import TimelineItem from './TimelineItem.svelte';
	import TimelineLoadingPlaceholderList from './TimelineLoadingPlaceholderList.svelte';

	let {
		listState,
		detailStatusKey = null,
		sourceKey = 'timeline',
	}: {
		listState: PagingState<UiTimelineV2>;
		detailStatusKey?: MicroBlogKey | null;
		sourceKey?: string;
	} = $props();

	const environmentSettings = useEnvironmentSettings();
	const timelineAppearanceState = $derived(environmentSettings.timelineAppearance());
	const contentPadding = 16;
	const cardItemGap = 8;
	const displayMode = $derived<TimelineDisplayMode>(
		timelineAppearanceState.type === 'Success'
			? timelineAppearanceState.data.timelineDisplayMode
			: defaultTimelineAppearance.timelineDisplayMode
	);
	const galleryMode = $derived(displayMode === 'Gallery');
	const cardStyleItems = $derived(!galleryMode && displayMode !== 'Plain');
	// A Success object is a paging snapshot, not a source identity.
	const itemKeys = $derived.by(() => {
		if (listState.type !== 'Success') return [];
		const occurrences = new Map<string, number>();
		return Array.from({ length: listState.itemCount }, (_, index) => {
			const key = timelineItemKey(listState.peek(index), index);
			const occurrence = occurrences.get(key) ?? 0;
			occurrences.set(key, occurrence + 1);
			return occurrence ? JSON.stringify(['duplicate', key, occurrence]) : key;
		});
	});
	const windowModel = untrack(() => {
		const model = new TimelineWindow();
		model.reconcile(itemKeys, 0, sourceKey);
		return model;
	});
	let body = $state<HTMLDivElement>();
	let revision = $state(0);
	let viewportTop = $state(0);
	let viewportHeight = $state(800);
	let focusedKey = $state<string | null>(null);
	const visibleRange = $derived.by(() => {
		revision;
		return windowModel.range(viewportTop, viewportHeight, 640);
	});
	const renderedRows = $derived.by(() => {
		revision;
		const indexes = Array.from({ length: visibleRange.end - visibleRange.start }, (_, i) => visibleRange.start + i);
		// Keep the focused article mounted when the reader scrolls with a keyboard/menu open.
		const focused = focusedKey === null ? -1 : itemKeys.indexOf(focusedKey);
		if (focused >= 0 && !indexes.includes(focused)) indexes.push(focused);
		indexes.sort((a, b) => a - b);
		return indexes.map((index, position) => ({
			index, key: itemKeys[index],
			before: windowModel.offset(index) - windowModel.offset(position ? indexes[position - 1] + 1 : 0),
		}));
	});
	const bottomSpacer = $derived.by(() => {
		revision;
		return windowModel.totalHeight - windowModel.offset(renderedRows.length ? renderedRows[renderedRows.length - 1].index + 1 : 0);
	});

	let scrollRoot: HTMLElement | null = null;
	let visibilityObserver: IntersectionObserver | undefined;
	let rowObserver: ResizeObserver | undefined;
	const rows = new Map<HTMLElement, { index: number; key: string }>();
	let requestedVisibleIndexes = new Set<number>();
	let adjustment = 0;
	let measuredMode: TimelineDisplayMode | undefined;

	function viewportBounds() {
		return scrollRoot
			? { top: scrollRoot.getBoundingClientRect().top + scrollRoot.clientTop, height: scrollRoot.clientHeight }
			: { top: 0, height: window.innerHeight };
	}
	function readViewport() {
		if (!body) return;
		const bounds = viewportBounds();
		viewportTop = bounds.top - body.getBoundingClientRect().top - (cardStyleItems ? contentPadding : 0);
		viewportHeight = bounds.height;
	}
	function requestItem(index: number) {
		if (galleryMode || listState.type !== 'Success' || index >= listState.itemCount || requestedVisibleIndexes.has(index)) return;
		requestedVisibleIndexes.add(index);
		listState.get(index);
	}
	function requestVisibleRows() {
		const bounds = viewportBounds();
		for (const [node, row] of rows) {
			const rect = node.getBoundingClientRect();
			if (rect.bottom > bounds.top && rect.top < bounds.top + bounds.height) requestItem(row.index);
		}
	}
	async function restoreViewport(top: number) {
		const version = ++adjustment;
		viewportTop = top;
		await tick();
		if (version !== adjustment || !body) return;
		const bounds = viewportBounds();
		const actual = bounds.top - body.getBoundingClientRect().top - (cardStyleItems ? contentPadding : 0);
		const delta = top - actual;
		if (Math.abs(delta) > 0.5) {
			if (scrollRoot) scrollRoot.scrollBy({ top: delta, behavior: 'instant' });
			else window.scrollBy({ top: delta, behavior: 'instant' });
		}
		readViewport();
		requestVisibleRows();
	}

	$effect.pre(() => {
		const keys = itemKeys;
		const source = sourceKey;
		const state = listState;
		const mode = displayMode;
		untrack(() => {
			if (state.type !== 'Success' || mode === 'Gallery') return;
			let top = windowModel.reconcile(keys, viewportTop, source);
			if (measuredMode !== undefined && measuredMode !== mode) top = windowModel.invalidateMeasurements(top);
			measuredMode = mode;
			revision++;
			requestedVisibleIndexes = new Set();
			void restoreViewport(top);
		});
	});

	$effect(() => {
		const element = body;
		if (!element) return;
		let parent = element.parentElement;
		while (parent && parent !== document.body && parent !== document.documentElement && !/(auto|scroll|overlay)/.test(getComputedStyle(parent).overflowY)) parent = parent.parentElement;
		scrollRoot = parent === document.body || parent === document.documentElement ? null : parent;
		visibilityObserver = typeof IntersectionObserver === 'undefined' ? undefined : new IntersectionObserver((entries) => {
			for (const entry of entries) if (entry.isIntersecting) {
				const row = rows.get(entry.target as HTMLElement);
				if (row) requestItem(row.index);
			}
		}, { root: scrollRoot, threshold: 0 });
		const measureRows = (nodes: HTMLElement[]) => {
			const entries: [string, number][] = [];
			for (const node of nodes) {
				const row = rows.get(node);
				if (row) entries.push([row.key, node.getBoundingClientRect().height]);
			}
			if (!entries.length) return;
			const top = windowModel.measure(entries, viewportTop);
			revision++;
			void restoreViewport(top);
		};
		let measureFrame = 0;
		const pendingMeasurements = new Set<HTMLElement>();
		const scheduleMeasurement = (nodes: HTMLElement[]) => {
			for (const node of nodes) pendingMeasurements.add(node);
			if (measureFrame) return;
			// A frame boundary keeps spacer writes out of the ResizeObserver delivery loop.
			measureFrame = requestAnimationFrame(() => {
				measureFrame = 0;
				measureRows([...pendingMeasurements]);
				pendingMeasurements.clear();
			});
		};
		rowObserver = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver((entries) => {
			scheduleMeasurement(entries.map((entry) => entry.target as HTMLElement));
		});
		for (const node of rows.keys()) {
			visibilityObserver?.observe(node);
			rowObserver?.observe(node);
		}
		const onScroll = () => { readViewport(); requestVisibleRows(); };
		let measuredWidth = element.clientWidth;
		const onResize = () => {
			readViewport();
			if (element.clientWidth !== measuredWidth) {
				measuredWidth = element.clientWidth;
				const top = windowModel.invalidateMeasurements(viewportTop);
				revision++;
				viewportTop = top;
			}
			scheduleMeasurement([...rows.keys()]);
		};
		window.addEventListener('scroll', onScroll, true);
		window.addEventListener('resize', onResize);
		const containerObserver = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(() => {
			if (element.clientWidth !== measuredWidth || viewportBounds().height !== viewportHeight) onResize();
		});
		containerObserver?.observe(element);
		if (scrollRoot) containerObserver?.observe(scrollRoot);
		untrack(() => { readViewport(); measureRows([...rows.keys()]); requestVisibleRows(); });
		return () => {
			visibilityObserver?.disconnect();
			rowObserver?.disconnect();
			containerObserver?.disconnect();
			cancelAnimationFrame(measureFrame);
			window.removeEventListener('scroll', onScroll, true);
			window.removeEventListener('resize', onResize);
		};
	});

	function observeRow(node: HTMLElement, row: { index: number; key: string }) {
		rows.set(node, row);
		visibilityObserver?.observe(node);
		rowObserver?.observe(node);
		queueMicrotask(requestVisibleRows);
		const onFocusIn = () => { focusedKey = rows.get(node)?.key ?? null; };
		const onFocusOut = () => { queueMicrotask(() => { if (!node.contains(document.activeElement) && focusedKey === rows.get(node)?.key) focusedKey = null; }); };
		node.addEventListener('focusin', onFocusIn);
		node.addEventListener('focusout', onFocusOut);
		return {
			update(next: { index: number; key: string }) { rows.set(node, next); },
			destroy() {
				rows.delete(node);
				visibilityObserver?.unobserve(node);
				rowObserver?.unobserve(node);
				node.removeEventListener('focusin', onFocusIn);
				node.removeEventListener('focusout', onFocusOut);
			},
		};
	}
</script>

<div
	class:card-mode={cardStyleItems}
	class:plain-mode={!cardStyleItems}
	class="timeline-list"
	style={`--timeline-content-padding: ${contentPadding}px; --timeline-card-gap: ${cardItemGap}px;`}
>
	{#if listState.type === 'Loading'}
		<TimelineLoadingPlaceholderList />
	{:else if listState.type === 'Error'}
		<div class="timeline-list-state">
			<div class="alert alert-error">
				<span>{listState.message ?? m.timelineUnableToLoadTimeline()}</span>
			</div>
		</div>
	{:else if listState.type === 'Empty'}
		<div class="timeline-list-empty">
			<span>{m.timelineEmpty()}</span>
		</div>
	{:else if galleryMode}
		<div class="timeline-gallery-slot"></div>
	{:else}
		<div class="timeline-list-body" bind:this={body}>
			<div class="timeline-window" role="feed" aria-busy={listState.isRefreshing}>
			{#each renderedRows as row (row.key)}
				{@const index = row.index}
				<div aria-hidden="true" style:height={`${row.before}px`}></div>
				{@const item = listState.peek(index)}
				<article
					class:card-row={cardStyleItems}
					class:plain-row={!cardStyleItems}
					class="timeline-row"
					use:observeRow={{ index, key: row.key }}
					aria-posinset={index + 1}
					aria-setsize={listState.itemCount}
				>
					<div class:rounded-box={cardStyleItems} class="timeline-row-content bg-base-100">
						{#if item}
							<TimelineItem {item} {detailStatusKey} />
						{:else}
							<PostLoadingPlaceholder />
						{/if}
					</div>

					{#if !cardStyleItems && index < listState.itemCount - 1}
						<div class="divider timeline-row-divider"></div>
					{/if}
				</article>
			{/each}
			<div aria-hidden="true" style:height={`${bottomSpacer}px`}></div>
			</div>

			{#if listState.appendState.type === 'Loading'}
				<div class="timeline-append-state">
					<progress class="progress progress-primary timeline-append-progress"></progress>
				</div>
			{:else if listState.appendState.type === 'Error'}
				<div class="timeline-append-state">
					<div class="alert alert-error timeline-append-error">
						<span>{listState.appendState.message ?? m.timelineUnableToLoadMorePosts()}</span>
						<button class="btn btn-sm" type="button" onclick={() => listState.retry()}>{m.actionRetry()}</button>
					</div>
				</div>
			{:else if listState.appendState.endOfPaginationReached}
				<div class="timeline-append-end">{m.timelineNoMorePosts()}</div>
			{/if}
		</div>
	{/if}
</div>

<style>
	.timeline-list {
		overflow-anchor: none;
		min-height: 100%;
		min-width: 0;
	}

	.timeline-list.card-mode {
		background: var(--color-base-200);
	}

	.timeline-list.plain-mode {
		background: var(--color-base-100);
	}

	.timeline-list-state {
		display: grid;
		min-width: 0;
		gap: 0.5rem;
		padding: var(--timeline-content-padding);
	}

	.timeline-list-empty {
		display: grid;
		min-height: 12rem;
		place-items: center;
		padding: var(--timeline-content-padding);
		color: color-mix(in oklab, var(--color-base-content) 56%, transparent);
		font-size: 0.9rem;
	}

	.timeline-gallery-slot {
		min-height: 100%;
		min-width: 0;
		background: var(--color-base-100);
	}

	.timeline-list-body {
		position: relative;
		min-width: 0;
		min-height: 100%;
	}

	.card-mode .timeline-list-body {
		padding: var(--timeline-content-padding);
		background: var(--color-base-200);
	}

	.plain-mode .timeline-list-body {
		background: var(--color-base-100);
	}

	.timeline-window {
		overflow-anchor: none;
	}

	.card-row {
		padding-bottom: var(--timeline-card-gap);
	}

	.timeline-row {
		min-width: 0;
	}

	.timeline-row-content {
		min-width: 0;
		width: 100%;
		overflow: hidden;
	}

	.timeline-row-divider {
		--divider-m: 0;
	}

	.timeline-append-state {
		min-width: 0;
		padding: 0.5rem 0;
	}

	.plain-mode .timeline-append-state {
		padding-inline: var(--timeline-content-padding);
	}

	.timeline-append-progress {
		display: block;
		width: 100%;
		height: 0.15rem;
	}

	.timeline-append-error {
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
	}

	.timeline-append-end {
		min-width: 0;
		padding: 0.5rem var(--timeline-content-padding);
		text-align: center;
		color: color-mix(in oklab, var(--color-base-content) 56%, transparent);
		font-size: 0.9rem;
	}
</style>
