import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { pathToFileURL } from 'node:url';
import { compile } from 'svelte/compiler';
import { render } from 'svelte/server';

let directory;
let FaIcon;
let PostHeader;
let Feed;
let compiledCss = {};

before(async () => {
	directory = await fs.mkdtemp(path.join(os.tmpdir(), 'flare-post-header-icons-'));
	const read = (rel) => fs.readFile(new URL(rel, import.meta.url), 'utf8');

	const faIconSource = (await read('../src/lib/components/FaIcon.svelte'))
		.replace("import { FontAwesomeIcon } from '@fortawesome/svelte-fontawesome';", "import FontAwesomeIcon from './StubFontAwesomeIcon.mjs';");
	const postHeaderSource = (await read('../src/lib/components/UiTimeline/post/PostHeader.svelte'))
		.replace("'$lib/components/FaIcon.svelte'", "'./StubFaIcon.mjs'")
		.replace("'$lib/components/PlatformIcon.svelte'", "'./StubPlatformIcon.mjs'")
		.replace("'$lib/deeplink/deepLink.svelte'", "'./stubDeepLink.mjs'")
		.replace("'./PostAvatar.svelte'", "'./StubAvatar.mjs'")
		.replace("'$lib/components/RichText.svelte'", "'./StubRichText.mjs'")
		.replace("'./postUtils'", "'./stubPostUtils.mjs'");
	const feedSource = (await read('../src/lib/components/UiTimeline/Feed.svelte'))
		.replace("'$lib/components/FaIcon.svelte'", "'./StubFaIcon.mjs'")
		.replace("'$lib/deeplink/deepLink.svelte'", "'./stubDeepLink.mjs'")
		.replace("'$lib/environment/environmentSettings.svelte'", "'./stubEnv.mjs'")
		.replace("'./post/postUtils'", "'./stubPostUtils.mjs'");

	await fs.writeFile(
		path.join(directory, 'stubDeepLink.mjs'),
		'export const useDeepLink = () => ({ canPerformClickEvent: () => false, performClickEvent: () => {} });\n',
	);
	await fs.writeFile(
		path.join(directory, 'stubEnv.mjs'),
		`export const useEnvironmentSettings = () => ({
			timelineAppearance: () => ({ type: 'Success', data: { absoluteTimestamp: false, showTranslateButton: true, showPlatformLogo: true, fullWidthPost: false, avatarShape: 'Circle' } }),
		});\n`,
	);
	await fs.writeFile(
		path.join(directory, 'stubPostUtils.mjs'),
		`export const defaultTimelineAppearance = { absoluteTimestamp: false, showTranslateButton: true, showPlatformLogo: true, fullWidthPost: false, avatarShape: 'Circle' };
export const translationLabel = (state) => state;
export const visibilityIcon = (visibility) => visibility;
export const visibilityLabel = (visibility) => visibility;\n`,
	);

	for (const [name, source] of [
		['StubFontAwesomeIcon', '<svg></svg>'],
		['StubFaIcon', '<script>let { name, size } = $props();</script><span data-fa-icon={name} data-size={size}></span>'],
		['StubPlatformIcon', '<script>let { icon, size } = $props();</script><span data-platform-icon={icon.type} data-size={size}></span>'],
		['StubAvatar', '<span></span>'],
		['StubRichText', '<script>let { text } = $props();</script><span>{text}</span>'],
		['FaIcon', faIconSource],
		['PostHeader', postHeaderSource],
		['Feed', feedSource],
	]) {
		const { js, css } = compile(source, { filename: `${name}.svelte`, generate: 'server' });
		compiledCss[name] = css?.code ?? '';
		const code = js.code
			.replaceAll("'svelte/internal/server'", JSON.stringify(import.meta.resolve('svelte/internal/server')))
			.replaceAll("'@fortawesome/free-solid-svg-icons'", JSON.stringify(import.meta.resolve('@fortawesome/free-solid-svg-icons')))
			.replaceAll("'@fortawesome/free-brands-svg-icons'", JSON.stringify(import.meta.resolve('@fortawesome/free-brands-svg-icons')));
		await fs.writeFile(path.join(directory, `${name}.mjs`), code);
	}

	({ default: FaIcon } = await import(pathToFileURL(path.join(directory, 'FaIcon.mjs'))));
	({ default: PostHeader } = await import(pathToFileURL(path.join(directory, 'PostHeader.mjs'))));
	({ default: Feed } = await import(pathToFileURL(path.join(directory, 'Feed.mjs'))));
});

after(async () => {
	if (directory) await fs.rm(directory, { recursive: true, force: true });
});

test('FaIcon formats numeric sizes as px and preserves relative CSS sizes', () => {
	const numeric = render(FaIcon, { props: { name: 'World', size: 16 } }).body;
	assert.match(numeric, /--fa-icon-size: 16px;/);

	const relative = render(FaIcon, { props: { name: 'World', size: '1em' } }).body;
	assert.match(relative, /--fa-icon-size: 1em;/);
});

test('PostHeader aligns all top-right icons and spinner to 1em text size', () => {
	const appearance = {
		showTranslateButton: true,
		showPlatformLogo: true,
		absoluteTimestamp: false,
		fullWidthPost: false,
		avatarShape: 'Circle',
	};
	assert.match(compiledCss.PostHeader, /\.meta-spinner[^{]*\{[^}]*width:\s*1em;[^}]*height:\s*1em;/);
	for (const [state, expectedIcons] of [
		['Translating', 3],
		['Failed', 4],
		['Translated', 3],
	]) {
		const { body } = render(PostHeader, {
			props: {
				post: {
					user: { name: 'Alex', clickEvent: { type: 'Noop' } },
					authorSubtitle: '@alex',
					visibility: 'Public',
					translationDisplayState: state,
					platformId: 'X',
					platformIcon: { type: 'Material', icon: 'X' },
					createdAt: { full: '2026-11-23 12:00', absolute: '12:00', relative: '5m' },
				},
				appearance,
				sideAvatarVisible: true,
				quoteHeader: false,
			},
		});
		const sizes = [...body.matchAll(/data-size="([^"]+)"/g)].map((match) => match[1]);
		assert.equal(sizes.length, expectedIcons, `Expected ${expectedIcons} icons for PostHeader ${state}`);
		assert.ok(sizes.every((size) => size === '1em'), `Unexpected icon sizes for ${state}: ${sizes.join(', ')}`);
		if (state === 'Translating') {
			assert.match(body, /loading-spinner meta-spinner/);
			assert.doesNotMatch(body, /loading-xs/);
		}
	}
});

test('Feed aligns top-right translation icons and spinner to 1em text size', () => {
	assert.match(compiledCss.Feed, /\.meta-spinner[^{]*\{[^}]*width:\s*1em;[^}]*height:\s*1em;/);
	for (const [state, expectedIcons] of [
		['Translating', 1],
		['Failed', 2],
		['Translated', 1],
	]) {
		const { body } = render(Feed, {
			props: {
				feed: {
					type: 'Feed',
					source: { name: 'Blog', icon: null },
					title: 'Update',
					description: null,
					media: null,
					clickEvent: { type: 'Noop' },
					translationDisplayState: state,
					createdAt: { full: '2026-11-23 12:00', absolute: '12:00', relative: '5m' },
				},
			},
		});
		const sizes = [...body.matchAll(/data-size="([^"]+)"/g)].map((match) => match[1]);
		assert.equal(sizes.length, expectedIcons, `Expected ${expectedIcons} icons for Feed ${state}`);
		assert.ok(sizes.every((size) => size === '1em'), `Unexpected feed icon sizes for ${state}: ${sizes.join(', ')}`);
		if (state === 'Translating') {
			assert.match(body, /loading-spinner meta-spinner/);
			assert.doesNotMatch(body, /loading-xs/);
		}
	}
});
