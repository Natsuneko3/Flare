<script lang="ts">
	import type { IconType } from '@flare/web-presenters/timeline.svelte';
	import FaIcon from './FaIcon.svelte';

	let { icon, size = 18 }: { icon: IconType; size?: number | string } = $props();
	const numericSize = $derived(typeof size === 'number' ? size : undefined);
	const imageStyle = $derived(typeof size === 'string' ? `width: ${size}; height: ${size};` : undefined);
	const imageSource = $derived(
		icon.type === 'Url'
			? icon.url
			: icon.type === 'FavIcon'
				? `https://icons.duckduckgo.com/ip3/${icon.host}.ico`
				: null
	);
</script>

{#if imageSource}
	<img
		class="platform-icon-image"
		src={imageSource}
		alt=""
		width={numericSize}
		height={numericSize}
		style={imageStyle}
		loading="lazy"
	/>
{:else}
	<FaIcon
		name={icon.type === 'Material' || icon.type === 'Mixed' ? icon.icon : 'Profile'}
		{size}
	/>
{/if}

<style>
	.platform-icon-image {
		display: block;
		flex: 0 0 auto;
		object-fit: contain;
	}
</style>
