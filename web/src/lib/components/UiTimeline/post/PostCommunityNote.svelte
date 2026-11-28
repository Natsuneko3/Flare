<script lang="ts">
	import RichText from '$lib/components/RichText.svelte';
	import type { UiCommunityNote } from '@flare/web-presenters/timeline.svelte';

	let { note }: { note: UiCommunityNote } = $props();
</script>

<div class="community-note rounded-box overflow-hidden border border-base-300">
	<h3 class="title">
		{#if note.url}
			<a href={note.url} target="_blank" rel="noopener noreferrer">{note.title}</a>
		{:else}
			<span>{note.title}</span>
		{/if}
	</h3>
	<RichText text={note.body} className="p-3" />
</div>

<style>
	.community-note {
		color: var(--post-text-readable, var(--color-base-content));
	}

	.title {
		display: block;
		background-color: var(--color-base-300);
		color: inherit;
		font-size: inherit;
		font-weight: 700;
		overflow-wrap: anywhere;
	}

	.title > :is(a, span) {
		display: block;
		padding: 0.5rem 0.75rem;
		color: inherit;
	}

	.title a:hover {
		text-decoration: underline;
	}
</style>
